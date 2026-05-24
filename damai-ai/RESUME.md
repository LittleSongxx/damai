## 大麦AI — 智能票务服务平台

**AI 应用开发** | 2025.10 - 2026.05

### 项目简介

为售票平台搭建的统一 AI 助手系统。普通用户通过对话完成规则咨询、票档查询和订单创建，下单前自动生成预览供人工确认；管理员可用自然语言查询 GMV 等业务数据、查看微服务日志与 JVM 运行指标。系统通过 Route-Skill-Execute 三层编排引擎统一承接购票、知识检索、通用问答、运维分析四类请求，自动完成意图路由与执行模式选择。

**技术栈**：Spring Boot 3.5、Spring AI 1.0、Qdrant、Elasticsearch、Redis/Redisson、RabbitMQ、Resilience4j、OpenTelemetry、Prometheus、Vue 3

### 职责与技术实现

**1. 统一助手编排与 Skills 系统**

设计分层降级技能选择策略，结合置信度自动决策直接执行、发起澄清或进入 ReAct 循环。自研 Skills 系统支持 DB 热更新，通过 Tool 白名单沙箱约束每项技能的调用边界，实现能力热插拔与安全隔离。

**2. 知识库建设与 CRAG 检索**

文档带版本与时效元数据入库，依内容哈希增量更新，过期自动下线。检索阶段以 Dense、HyDE、Sparse 三通道多路召回，经 RRF 融合与 Rerank 精排，时效过滤后 LLM 对结果进行三元评估——正确则直接精炼生成，模糊则拆解子问题补充检索，错误则改写查询重试，持续低置信度主动拒答。离线评测 Recall@5 超 0.88、NDCG@5 超 0.85、Faithfulness 超 0.93。

**3. 纵深安全防护与 HITL 审批**

构建认证、RBAC、输入护栏、JSON Schema 校验、PolicyGuard、输出护栏递进防线，输出护栏集成 PII 脱敏、毒性拦截与幻觉检测。下单等高危操作生成待审批 Action 经策略校验后人工确认。连续多轮检索无果或识别负面情绪时自动建议转人工客服，形成完整兜底机制。NL2SQL 引入 AST 语法树校验拦截危险操作，合成合法率超 99%。

**4. MCP 运维工具集与弹性可观测**

自研 Log 与 Metrics 两个 MCP Server，暴露日志多维检索、traceId 链路串联、JVM 健康巡检等 15 个工具。对 LLM、Qdrant、ES、WebSearch 四类外部依赖独立熔断，线程池溢出转 RabbitMQ 异步队列防止请求丢失，基于 OpenTelemetry 与 Micrometer 实现全链路追踪与指标采集。