# damai-ai 面试深度问答手册

> 本手册基于 damai-ai 项目实际代码编写，覆盖 Spring AI、Agent、RAG、MCP、Function Calling、NL2SQL、Guardrails 等 AI 应用开发核心技术。  
> 定位为 **AI 应用开发岗位** 面试备战，与 damai-pro 后端岗位手册同等地位。  
> 每条问答带有 **加粗核心要点**。

---

## 一、AI 购票（Tool Calling + 状态机）

### Q1：AI 购票的完整流程是怎样的？

1. 用户在对话中说"帮我买两张周杰伦北京演唱会 680 的票"
2. `AssistantSkillSelector` 根据触发关键词（"买"、"购票"）判断调用 **`PurchasePrepareSkill`**
3. `BusinessSkillParameterExtractor` 从自然语言中**提取槽位**：节目名、城市、票档、数量、购票人信息
4. Spring AI 通过 **Function Calling** 调用 `ProgramSearchFunction` 查询节目 → `CreateOrderFunction` 生成购票预览
5. 技能返回 `AssistantActionPreviewVo`（预览摘要），同时创建 **`AiAction`（待审批动作）**
6. 用户在前端确认后 → `purchasePreparationService` 调用 damai-pro 的订单接口
7. 返回订单结果给用户

### Q2：对话状态机是怎么实现的？

`AssistantRunService` 维护 `AiRun` 实体，记录每次对话运行的：
- `runId`：唯一运行标识
- `status`：运行状态（PENDING → RUNNING → COMPLETED / FAILED）
- `conversationId`：所属会话
- `skillId`：当前执行的技能
- `currentStep`：当前执行步骤

`AssistantSkillContext` 封装了当前运行的**完整上下文**，包括用户消息、历史记忆、用户画像等。技能通过 `routeType` 分类：`BUSINESS`（购票/客服）、`OPS`（运维）、`KNOWLEDGE`（知识问答）、`GENERAL`（闲聊）。

### Q3：Spring AI 的 Tool Calling 底层怎么实现的？

Spring AI 在发送请求时，将注册的工具的 **JSON Schema** 作为 tools 参数传给 LLM：
1. LLM 判断是否需要调用工具 → 返回 tool call（含函数名 + 参数 JSON）
2. Spring AI 框架**自动解析**函数名和参数，反射调用 Java 方法
3. 将调用结果**回填到对话上下文**，再次发送给 LLM 生成最终回答

整个过程在 `ChatClient.prompt().tools(...).call()` 中**自动完成**，开发者只需定义 Function Bean（`@Description` 注解的方法）。

### Q4：人在环审批（Human-in-the-loop）怎么实现的？

`PurchasePrepareSkill.execute()` 创建 `AiAction`（待审批动作），状态为 `PENDING`，同时返回给前端。前端展示预览和确认按钮：
- 用户**确认** → 前端调用审批接口 → `AiAction` 状态变为 `APPROVED` → 执行 `purchasePreparationService.purchase()`
- 用户**拒绝** → `AiAction` 状态变为 `REJECTED` → 释放预留资源

这种设计确保**AI 不能直接下单，用户始终有最终决策权**。

---

## 二、RAG 客服（CRAG 混合检索）

### Q5：你的 RAG 管线具体是怎么设计的？

完整管线（**CRAG 模式**）：

```
Query Rewrite（LLM 改写查询）
  → Complexity Grading（简单/中等/复杂分级）
  → [Embedding + BM25 + HyDE] 三路并行召回
  → Evidence Gate 对各通道结果分别过滤（融合前）
  → RRF 加权融合排序
  → Rerank 精排
  → 后处理链：去重 → Lost-in-Middle 重排 → 时效性校验 → 父块提升
  → CRAG 评估（LLM as Judge）
     ├─ CORRECT → 轻量精炼
     ├─ AMBIGUOUS → 扩展 TopK + 子问题拆解重检
     └─ INCORRECT → LLM 改写查询 + 2x TopK 重检
  → Document 解析输出
```

### Q6：三路并行召回各自用的什么技术？

- **Embedding（稠密检索）**：`DenseSearchChannel`，将查询和文档分别向量化（通过 Embedding 模型），计算**余弦相似度**，从 Qdrant 向量数据库检索 TopK
- **BM25（稀疏检索）**：`SparseSearchChannel`，基于**词频-逆文档频率**的经典检索算法，对关键词匹配效果好，互补于语义检索
- **HyDE（假设文档检索）**：`HydeSearchChannel`，让 LLM 先生成一份**假设答案文档**，再以此文档的向量去做检索。当用户查询简短模糊时效果显著——假设文档比原始查询更接近真实文档的语义分布

### Q7：为什么三路要并行？为什么不全用 Embedding？

- **Embedding 擅长语义**但可能忽略精确关键词（如"VIP 票档"）
- **BM25 擅长关键词**但不理解语义（"周杰伦"和"Jay Chou"不是同一向量）
- **HyDE 擅长模糊意图**但依赖 LLM 质量

**互补覆盖**：三路并行搜索，任一通道挂掉不影响其他通道（有 30 秒超时熔断），最终通过 RRF 融合取长补短。

### Q8：RRF（倒数排名融合）怎么计算的？

`RagFusionSupport.weightedReciprocalRankFusion()` 实现：

```
RRF_score(doc) = Σ 1 / (k + rank_i(doc))
```

其中 k=60（可配置），rank_i 为文档在第 i 个通道中的排名。每个通道的结果按分数排序后分配排名，再通过 RRF 公式合并，**天然处理了不同通道分数不可比的问题**。

### Q9：Evidence Gate 做什么？为什么放在 RRF 之前？

`EvidenceGatePostProcessor`：对每个检索结果，让 LLM 判断**该文档是否真正与用户问题相关**。不相关的直接丢弃。

放在 RRF 之前是因为：如果先融合再 gate，一个通道的噪音会**污染**所有通道的结果。先按通道分别 gate，再去融合，保证融合的是**被各通道确认相关**的文档。

### Q10：CRAG 的三路分类（CORRECT/AMBIGUOUS/INCORRECT）怎么判断？

`KnowledgeRetrievalEvaluator.assess()` 使用 **LLM as Judge** 评估检索质量：
- **语义相关性**：检索到的文档是否与问题相关
- **覆盖度**：是否覆盖了问题的各个方面
- **冲突检测**：检索结果之间是否存在矛盾

根据评估分数分类：
- **CORRECT**（>0.7）：检索质量合格，轻量精炼去噪后输出
- **AMBIGUOUS**（0.3-0.7）：扩展 TopK 至 3 倍，拆解子问题重检
- **INCORRECT**（<0.3）：LLM 改写查询语句 + 2 倍 TopK 重检

### Q11：你怎么评估 RAG 系统的效果？

项目有完整的 **RAG 评估体系**：
- `RagEvalDataset`：评估数据集实体
- `RagEvalBaselineService`：基线对比（对比不同检索策略的指标）
- `RagEvalScorer`：多维度评分器（相关性、精确度、召回率）
- `RagEvalReportService`：自动生成评估报告

支持**离线评估**（用标注数据集批量测试）和**在线追踪**（`RagOnlineTraceService` 记录线上每次检索的完整链路）。

---

## 三、MCP（Model Context Protocol）与运维韧性

### Q12：MCP 是什么？你在项目中怎么用的？

**MCP 是 Anthropic 提出的 AI 工具调用标准协议**，定义了 Client-Server 通信格式。本项目实现了三个 MCP Server 工具：

- **LogMcpTools**：日志查询（关键词搜索、时间范围、日志级别过滤）
- **MetricsMcpTools**：指标查询（Prometheus 兼容格式）
- **Nl2SqlMcpTools**：自然语言转 SQL 查询

每个 MCP Tool 通过 `@McpTool` 注解暴露，LLM 通过 Function Calling 调用，MCP 协议保证了**工具调用的标准化和可互操作性**。

### Q13：NL2SQL 的安全校验做了哪些？为什么要做这么严格？

`Nl2SqlSafetyValidator.validate()` 的校验链路：

1. **SQL 注解禁止**：正则检测 `--`、`/* */`、`#` 等注释符号，防止绕过后续检查
2. **JSqlParser AST 解析**：解析为抽象语法树
3. **仅允许 SELECT**：`if (!(statement instanceof Select))` 直接拒绝
4. **禁止多语句**：`statements.size() != 1` 拒绝
5. **表名白名单**：与 `schemaService.allowedTableNames()` 对比，不在白名单的拒绝
6. **禁止分片表名**：正则 `*_\\d+$` 匹配，拒绝直接访问物理分片表
7. **禁止 SELECT ***：正则 `(?is)select\s+\*` 拒绝，必须显式列名
8. **敏感字段过滤**：`properties.getSensitiveColumns()` 中的字段名出现即拒绝
9. **自动补 LIMIT**：没有 LIMIT 的 SQL 自动追加 `LIMIT {maxRows}`

**严格原因**：LLM 生成的 SQL 不可信——SQL 注入、删库、敏感数据泄露等问题一旦发生就是**生产事故**。AST 校验是最后的防线，**宁可误杀，不能漏放**。

### Q14：诊断链（Diagnostic Chain）怎么工作的？

`DiagnosticPlanner.plan(prompt)` 将运维问题分解为多步诊断推理链：
1. 第一步：查日志（LogMcp）→ 定位异常时间点
2. 第二步：查指标（MetricsMcp）→ 确认异常指标（CPU、QPS、错误率）
3. 第三步：问数（Nl2Sql）→ 业务数据是否异常（订单量、支付成功率）
4. 第四步：综合分析 → 给出根因和修复建议

链中的每步是**条件分支**：如果日志找到异常，下一步查相关服务的指标；如果日志无异常，下一步跳过指标直接问数。

### Q15：Agentic 循环中的自修复重试怎么实现的？

`SkillAgentLoopService.executeWithRetry()` 遵循 **Anthropic "Building Effective Agents"** 的 Agent 模式：

1. 第一轮：LLM 调用工具 → 得到答案
2. `isIncompleteAnswer(text)` 判断答案是否不完整（含"没有找到""暂无相关"等消极词汇）
3. 如果不完整 → 构建**替代策略 Prompt**，触发下一轮（最多 4 轮）
4. 替代策略示例：扩大时间窗口、检查上下游服务、对比基线、建议人工介入
5. 直到答案完整或达到最大轮数

这与 ReAct 模式不同——**不是每步都决策**，而是整个工具调用完成后才评估是否需要重试，**减少 LLM 调用次数**。

### Q16：模型回退（Fallback）怎么实现的？

`ResilientChatService.call()` 流程：
1. 先检查 **配额**（`QuotaTracker`），超限直接返回"今日额度已用完"
2. 通过 `CircuitBreakerService` 熔断保护调用 Primary Model
3. Primary Model 异常 → **自动切换到 Fallback Model**
4. 记录每次调用的 token 消耗和延迟指标
5. 熔断器统计失败率，超过阈值自动打开熔断

`LlmFallbackProperties` 配置了 Primary/Fallback 的模型名、超时、重试次数。

---

## 四、Skill 系统架构

### Q17：Skill 系统是怎么设计的？如何注册和发现？

**插件式架构**，基于 Spring 的 `List<AssistantSkill>` 自动注入：

1. 所有实现 `AssistantSkill` 接口的 Bean 自动注入到 `AssistantSkillRegistry`
2. Registry 按 `routeType`（BUSINESS/OPS/KNOWLEDGE/GENERAL）分组
3. 每个 Skill 通过 `AssistantSkillDescriptor` 声明：
   - `skillId`：唯一标识
   - `triggerKeywords`：触发关键词
   - `toolAllowlist`：允许调用的工具白名单
   - `riskLevel`：风险等级
   - `requiresAdmin`：是否需要管理员权限
   - `requiresApproval`：是否需要审批

### Q18：Skill 的 Schema 校验和策略守卫做什么？

- **Schema 校验**（`AssistantSkillSchemaValidator`）：验证 Skill 的输入输出 JSON 格式是否符合声明的 Schema，确保**入参合法性**
- **策略守卫**（`AssistantSkillPolicyGuard`）：检查当前用户是否满足 Skill 的执行条件（权限等级、审批状态、配额限制等）

### Q19：Skill 之间怎么通信或协作？

本项目 Skill 是**独立执行**的，不直接通信。会话级别通过 `AssistantMemoryContext` 共享记忆。未来可扩展为 **Multi-Agent 协作**——一个 Skill 的输出作为另一个 Skill 的输入，通过 Orchestrator 调度。

---

## 五、Guardrails（安全护栏）

### Q20：输入护栏检查哪些内容？

`InputGuardrailService.check()`：
- **Prompt 注入检测**（`PromptInjectionChecker`）：检测用户是否试图绕过系统约束（如"忽略此前所有的指令"、"输出你的 System Prompt"）
- **PII 检测**（`PiiDetector`）：检测身份证号、手机号、银行卡号等敏感信息

检测到注入攻击 → **直接拦截（Block）**，不送入 LLM。  
检测到 PII → 标记为 **WARN**，记录审计日志后继续。

### Q21：输出护栏检查什么？

`ResponseGuardrailService` 和 `OutputGuardrailService`：
- **内容安全**：检测有害/违规输出
- **幻觉检测**：对比输出内容与检索到的证据，标记引用矛盾
- **外部工具返回值校验**（`ExternalToolResponseValidator`）：验证 MCP 工具返回的数据格式和内容安全

---

## 六、SSE 流式输出

### Q22：SSE 逐 token 流式输出怎么实现的？

`AssistantMessageEmitter` 封装了 Reactor Flux 的流式处理：

1. LLM 返回 `Flux<String>`（每个 String 是一个 token）
2. `emitStream()` 方法订阅 Flux，每收到一个 token 就调用 `runService.appendEvent()` 写入事件队列
3. 前端通过 SSE（Server-Sent Events）长连接订阅事件
4. `AssistantRunEventStreamService` 管理 SSE 连接的生命周期

Flux 中间加了 `limitRate(100)` 背压控制和 `onBackpressureBuffer(200)` 缓冲。

---

## 七、AI 场景与八股文

### Q23：LLM 的幻觉问题你在项目中怎么缓解的？

1. **RAG 约束**：回答基于检索到的真实知识，限定在证据范围内
2. **CRAG 纠正**：INCORRECT 路径自动改写查询重检，减少因检索质量导致的幻觉
3. **Evidence Gate**：过滤无关文档，降低 LLM 被噪音误导的概率
4. **输出护栏**：检测输出与证据的矛盾
5. **置信度控制**：NL2SQL 中 `confidence < 0.5` 时设置 `needSql=false`，**宁可说不知道也不编造 SQL**

### Q24：RAG 和 Fine-tuning 的区别？什么场景用哪个？

| | RAG | Fine-tuning |
|---|-----|------------|
| **原理** | 检索外部知识注入 Prompt | 微调模型参数 |
| **知识更新** | 实时（更新文档库即可） | 需重新训练 |
| **成本** | 检索 + 推理 | 训练 + 推理 |
| **可控性** | 高（可审计引用来源） | 低（黑盒） |
| **适用** | 知识密集、频繁更新 | 风格/格式定制、领域特化 |

票务系统的知识（演出、场馆、政策）**频繁变化**，RAG 天然适合。

### Q25：向量数据库选型为什么选 Qdrant 而不是 Milvus 或 Pinecone？

- **Qdrant**：Rust 实现，高性能，支持**过滤 + 向量混合查询**，开源且 Rust 运行时安全性高
- Milvus：功能更全面但**部署重**，适合超大规模
- Pinecone：SaaS，不可私有部署

本项目数据量适中，Qdrant 的**单机部署**即可满足，且 Rust 实现的稳定性在生产环境中验证良好。

### Q26：Embedding 模型用的什么？为什么？

通过 Spring AI 的 `EmbeddingModel` 抽象，**可替换**。本项目配置支持 OpenAI text-embedding-3 系列和国内通义千问 Embedding。选择依据：
- **维度**：1024/1536 维，平衡精度和存储
- **中文效果**：通义千问 Embedding 对中文语义理解更优
- **成本**：按 token 收费，需做预算控制

### Q27：RAG 中怎么处理文档切片（Chunking）？

项目中的 `RagIngestionPipeline`（基于 RabbitMQ 异步消费）：
1. 文档切割：按**语义段落**切分（Markdown 标题层级、自然段）
2. 重叠窗口：相邻 chunk 保留 20% 重叠，**防止信息被切断**
3. 元数据保留：每个 chunk 携带文档标题、章节、页码等
4. **父块提升**（`ParentBlockElevationPostProcessor`）：检索到子块时，自动带上父块上下文

### Q28：MCP 协议和传统 REST API 有什么区别？

| | MCP | REST API |
|---|-----|----------|
| **标准化** | 约定工具描述格式（JSON Schema） | 自定义 |
| **工具发现** | 自动列出所有可用工具 | 需文档或额外元数据 |
| **传输** | JSON-RPC over stdio/SSE | HTTP |
| **生态** | LLM 工具调用生态 | 通用 Web 生态 |

MCP 的核心价值是**让 LLM 能自动理解和使用工具**——标准化工具描述 + 参数 Schema，任何 MCP Server 都可以被任何 MCP Client 发现和调用。

### Q29：Function Calling、MCP、Skill 三者是什么关系？

- **Function Calling**：Spring AI 内置机制，将 Java 方法暴露为 LLM 可调用的工具（底层是 OpenAI/Anthropic 的 tool_calls API）
- **MCP**：标准化的工具调用协议，定义了 Client-Server 通信格式。项目中的 MCP 工具 (LogMcpTools 等) 底层通过 Function Calling 机制暴露
- **Skill**：更高层的业务抽象，包含**意图识别、上下文管理、策略控制、多工具编排**。一个 Skill 可以调用多个 Function/MCP 工具

三者的层次关系：**Skill → 编排多个 Tool → Tool 通过 MCP 协议或 Function Calling 执行**

### Q30：你项目中 Agent 的决策粒度怎么样？和市面上的 Agent 框架有什么区别？

本项目采用 **Orchestrator-Worker 模式**（Anthropic 推荐）：
- Orchestrator（`AssistantSkillSelector` + `SkillAgentLoopService`）负责**高层决策**：选哪个 Skill、答案是否完整、是否重试
- Worker（各 Skill 实现）负责**低层执行**：具体的工具调用链

与 LangChain Agent 的区别：LangChain 在**每个工具调用后都做决策**（ReAct 循环），本项目在**整轮调用完成后才评估**，减少了 70% 以上的 LLM 调用次数，**控制成本**。

### Q31：你怎么管理 LLM 调用的成本？

1. **Token 预算**（`TokenBudgetManager`）：统计每次调用的 token 消耗
2. **配额控制**（`QuotaTracker`）：用户/会话维度的调用限额
3. **缓存**：NL2SQL 的 SQL 执行结果缓存（相同 SQL 不重复执行）
4. **熔断**：异常比例超阈值时停止调用，避免无效消耗
5. **模型回退**：主模型故障时切到备用模型，两者可能计费不同

### Q32：SSE 和 WebSocket 在 AI 对话场景下的选择？

- **SSE**：单向推送（服务端→客户端），HTTP 协议，**实现简单，天然支持断线重连，适合 LLM 流式输出**
- **WebSocket**：双向通信，TCP 连接，实时性更高但需要额外的心跳和重连逻辑

AI 对话中**服务端主动推送 token，客户端不需要频繁回传**，SSE 是更自然的选择。WebSocket 适合需要双向实时交互的场景（如协同编辑）。

### Q33：多轮对话的上下文怎么管理？

项目中通过两层上下文管理：
1. **AssistantMemoryService**：管理会话级别的 ChatMemory（Spring AI 的 `ChatMemory` 接口），自动追加到每次 LLM 调用的 messages 列表
2. **AssistantMemoryKeyService**：生成 user + conversation 级别的唯一键
3. **记忆摘要**：`AiConversationMemorySummary` 实体存储长对话的定期摘要，控制上下文窗口长度
4. **NL2SQL 独立上下文**：`Nl2SqlMultiTurnContextService` 为 NL2SQL 维护独立的上下文窗口，记录历史 SQL 问答对用于指代消解

### Q34：Prompt 模板怎么管理的？

`PromptTemplateLoader` 支持 **StringTemplate (.st)** 格式的模板文件（`resources/prompt/*.st`），通过 `render()` 方法填充变量。模板化 Prompt 的好处：
- **版本管理**：Prompt 在 Git 中可追溯
- **A/B 测试**：快速切换不同 Prompt 版本
- **非开发人员可维护**：业务人员修改 `.st` 文件无需改 Java 代码

---

> 本手册覆盖 damai-ai 项目的 AI 购票、RAG 客服、MCP 运维、NL2SQL、Guardrails、Skill 架构、SSE 流式等核心模块，并延伸至 LLM 幻觉、RAG vs Fine-tuning、向量数据库选型、Function Calling 原理、Agent 设计模式等 AI 应用开发核心八股文。共计 34 组问答，覆盖 AI 应用开发面试的全部高频考点。