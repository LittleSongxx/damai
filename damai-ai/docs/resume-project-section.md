# 大麦 AI 智能助手平台

**技术栈**：Spring Boot 3 · Spring AI · Qdrant (gRPC) · Elasticsearch · MySQL · RabbitMQ · Prometheus · Vue 3 · SSE

**项目定位**：面向票务领域的 AI 智能助手平台，围绕 **Assistant Run → Skill → Tool** 工作流，落地了 Hybrid RAG 闭域知识问答、LLM Tool Calling 购票业务、联网搜索、NL2SQL 运维查询、MCP 日志/指标监控等五大能力模块。后端 Java 代码 281 个源文件，单元测试 91 个全量通过；闭域知识语料 34 篇 Markdown、236 个 FAQ 分段。

---

## 一、Skill 执行引擎与治理体系

设计并实现了统一的 **SkillExecutor** 流水线，覆盖 Skill 全生命周期：

1. **路由 → 选择**：`AssistantSkillSelector` 对 4 条路由（KNOWLEDGE / BUSINESS / GENERAL / OPS）下注册的 Skill 进行候选过滤与关键词加权打分，支持前端 `skillHint` 直选和模型自动匹配两种模式。
2. **策略守卫**：`AssistantSkillPolicyGuard` 在执行前校验 Skill 启用状态、RBAC 权限（管理员/普通用户）及风险等级（LOW / MEDIUM / HIGH / CRITICAL），执行后强制校验 `requiresApproval` Skill 必须产出待审批动作。
3. **Schema 校验**：`AssistantSkillSchemaValidator` 基于每个 Skill 描述符中声明的 `inputSchemaJson` / `outputSchemaJson`，对输入输出必填字段做运行时校验。
4. **工具沙箱**：`AssistantSkillToolScope` 利用 `InheritableThreadLocal` 为每次执行划定工具白名单作用域，`AssistantToolInvoker` 在调用前断言工具是否在白名单内，越权调用立即阻断。支持精确匹配、前缀通配（`ops.*`）和全通配（`*`）。
5. **DB 热更新**：`AssistantSkillDefinitionService` 将 Java 代码中硬编码的 Skill 描述符与 DB `ai_skill` 表逐字段合并，管理员可在线修改名称、关键词、工具白名单、风险等级等配置，变更写入 `ai_skill_change_log` 审计表。
6. **SSE 流式 / 分块发射**：`AssistantMessageEmitter` 区分两条路径——`KnowledgeSkill` 返回 `Flux<String>` 后走真流式 SSE（逐 token `MESSAGE_DELTA` 事件），其他 Skill 走 180 字符分块发射。
7. **14 种生命周期事件**：`run.started` → `route.selected` → `skill.started` → `retrieval.*` → `tool.*` → `message.delta` → `action.required` → `run.completed`，贯穿执行全链路，前端可基于事件流渲染进度和证据卡片。
8. **Skill Eval 框架**：`AssistantSkillManagementService` 支持对任意 Skill 执行自动化评测，遍历 `ai_skill_eval_case` 用例、在完整策略/Schema/工具沙箱环境下运行，校验输出是否包含期望关键词或排除禁止内容，结果写入 `ai_skill_eval_run` 表。

---

## 二、Hybrid RAG 闭域知识问答（CRAG）

实现了完整的 **Corrective RAG** 流水线，用于平台规则问答（退票、入场、实名、限购等）：

1. **检索规划**：`KnowledgeRetrievalPlanner` 生成检索计划，固定参数 topK=8、证据源上限 6 条、上下文预算 4000 字符。
2. **混合检索**：`HybridSearchService` 执行 Qdrant 向量检索（`text-embedding-v3` 1024 维）+ Elasticsearch BM25 稀疏检索 + RRF 融合排序。
3. **LLM Query Rewrite + Multi-query**：`AdvancedQueryService.rewriteQuery()` 将口语化查询改写为 1 个主查询 + 2 个变体查询，提升召回率。
4. **qwen3-rerank 精排**：`RerankService` 调用 DashScope qwen3-rerank API 对候选文档精排，失败时降级为关键词重叠打分。
5. **上下文压缩**：`ContextualCompressionService` 用 LLM 从文档中只提取与查询相关的核心句子，过滤无关段落；失败时降级为 400 字符截断。
6. **结构化规则补充**：`StructuredRuleSupportService` 维护 8 类规则关键词映射（退票、实名、电子票、支付、配送、安检、限购、防诈骗），命中后注入规则提示文档作为补充证据。
7. **五维置信度评估**：`KnowledgeRetrievalEvaluator` 加权打分——向量 Top1 分数 (0.35) + 稀疏 Top1 归一化 (0.25) + 双路重叠 (0.15) + 源数量 (0.15) + 来源多样性 (0.10)，阈值 ≥0.72 HIGH / ≥0.45 MEDIUM / <0.45 LOW。
8. **CRAG 纠正循环**：置信度 LOW 且来源 <4 时启动三路纠正——(a) 纠正查询拼接最长关键词 (b) 子问题分解逐一检索 (c) HyDE 假想文档嵌入检索，合并去重后重新评估。
9. **接地提示组装**：`KnowledgePromptAssemblyService` 将选中文档在字符预算内拼接为证据块，封装为严格接地 Prompt——只能基于证据回答、不得虚构规则。
10. **低置信度拒答**：评估为 LOW 时直接返回拒绝回答，不调用 LLM 生成，杜绝幻觉。
11. **全链路追溯**：每次检索保存 `AiRetrieval` 实体（稠密/稀疏/融合/最终命中、置信度、纠正动作），发射 `retrieval.started` / `retrieval.completed` 事件。

---

## 三、LLM Tool Calling 购票业务

1. **业务技能**：`BusinessSkill` 使用 Spring AI `ChatClient` Tool Calling，LLM 自主选择 `recommendPrograms`（节目推荐）、`searchPrograms`（节目搜索）、`getProgramDetail`（详情查询）、`preparePurchase`（购票预览）四个工具完成购票全链路。
2. **购票准备 + 人在环**：`PurchasePrepareSkill` 声明 `requiresApproval=true`，由 `BusinessSkillParameterExtractor` 提取购票参数（LLM 结构化输出 + 正则降级），调用 `PurchasePreparationService` 匹配节目、票档、实名购票人后生成下单预览，创建 `AiAction` 待审批动作。前端展示预览卡片，用户确认后才可创建订单。策略守卫强制保障：声明需审批的 Skill 结果必须包含 `pendingAction`。
3. **参数提取双保险**：`BusinessSkillParameterExtractor` 先尝试 LLM `StructuredOutputService` 结构化抽取，失败后降级为正则：城市关键词匹配、手机号 `1[3-9]\d{9}`、身份证号、价格 ≥50 元、数量"N张"。

---

## 四、联网搜索通用问答

`GeneralChatSkill` 处理开放域问答（艺人介绍、娱乐资讯等）：`GeneralSearchPlanner` 基于 17 个意图关键词判断是否需要联网搜索，触发时通过 `WebSearchService` 获取搜索结果（前 5 条标题/链接/摘要），注入 Prompt 作为联网证据，LLM 基于证据生成回答。

---

## 五、NL2SQL 运维查询（管理员专属）

1. **NL2SQL 编排**：`Nl2SqlOrchestrator` 执行完整流程——Schema 检索 → LLM 生成 SQL → AST 安全校验 → 执行 → 失败时 LLM 修复重试。
2. **七层安全校验**：`Nl2SqlSafetyValidator` 使用 JSQLParser 解析 AST，强制 SELECT-only、仅允许白名单表、禁止敏感字段（mobile/password/id_number 等）、禁止 `SELECT *`、禁止 SQL 注释、禁止危险函数（sleep/benchmark/load_file 等）、强制 `LIMIT ≤100`。
3. **RBAC 管控**：`AiPermissionService` 基于配置的管理员用户 ID 列表控制 OPS 路由和 Skill 访问，非管理员完全无法触达运维能力。

---

## 六、MCP Server 日志 / 指标监控

独立部署两个 MCP Server 微服务，通过 Spring AI `@Tool` 注解暴露为 LLM 可调用工具：

1. **日志 MCP**（`damai-mcp-log-service`）：基于 Easy-ES 查询 Elasticsearch，提供 7 个工具——服务列表、关键词搜索、traceId 链路追踪、最新日志、错误日志、警告日志、日志统计、按类名/方法名搜索。traceId 查询自动按服务分组展示调用链路。
2. **指标 MCP**（`damai-mcp-metrics-service`）：基于 Prometheus HTTP API，提供 8 个工具——服务列表、JVM 堆内存（含内存池明细）、GC 指标（次数/耗时/平均耗时）、线程指标（活跃/峰值/守护/状态分布）、CPU 使用率、单服务健康概览、全服务健康巡检。健康评估阈值：内存 >90% 告警、CPU >80% 告警。

---

## 七、异步记忆与用户画像

1. **RabbitMQ 异步触发**：`AssistantRunCompletedPublisher` 在每次 Run 完成后发布消息到 `damai.ai.memory.topic` 交换机，`AssistantRunCompletedConsumer` 消费后异步刷新记忆和画像，主链路零阻塞。
2. **对话记忆压缩**：`AssistantMemoryService` 加载最近完成的 Run，用 LLM 将多轮对话压缩为简洁摘要并持久化，下次对话时注入 Prompt 的"历史摘要"部分。
3. **用户画像提取**：`AssistantUserProfileService` 从用户消息中提取偏好标签和画像摘要（关键词匹配 + LLM 标签提取），持久化并注入 Prompt 的"用户偏好画像"部分，实现跨会话个性化。
4. **情景记忆**：`EpisodicMemoryService` 记录用户交互事件，支持时间衰减（权重 < 0.01 自动失效）。
5. **上下文构建**：`AssistantSkillContext.buildUserPrompt()` 自动将画像 + 历史摘要 + 当前问题组装为统一 Prompt 模板，所有 Skill 共享。
