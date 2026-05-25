## 大麦AI — 智能票务服务平台

**AI 应用开发** | 2025.10 - 2026.06

### 项目简介

为售票平台搭建的统一 AI 助手，用户通过对话完成规则咨询、票档查询与购票确认，管理员可用自然语言查询业务数据与微服务指标。系统基于 Route-Skill-Execute 编排引擎承接四类请求，结合情感分析与智能升级构建 AI 到人工客服的完整链路。

**技术栈**：Spring Boot 3.5、Spring AI 1.0、Qdrant（gRPC）、Elasticsearch 7.17、Redis/Redisson、RabbitMQ、Resilience4j、OpenTelemetry、Vue 3

### 职责与技术实现

**1. 多轮对话与智能客服**

自研对话状态机支撑购票与节目查询的多轮引导，FAQ 通过关键词+语义向量两层匹配提升命中效率。结合情感分析、危机关键词与负向 streak 检测触发人工升级，工单自动携带对话摘要。

**2. 助手编排与多模式执行**

基于 Route-Skill-Execute 实现技能路由、置信度门控与执行模式决策，支持执行、澄清和重试。Skills 支持 DB 热更新与工具调用沙箱，对话后异步压缩记忆并提取用户画像。

**3. 多通道 RAG 检索**

文档按版本、时效和内容哈希管理，采用父子块分层分块增强召回与上下文还原。Dense、HyDE、Sparse 三路召回经 RRF 融合、Rerank 精排与后处理过滤，低置信度拒答；离线 Recall@5 超 0.88、NDCG@5 超 0.85、Faithfulness 超 0.93。

**4. 智能运维**

将日志、指标与 NL2SQL 工具以 @Tool 集成，通过 MCP Server STDIO 暴露给运维助手。NL2SQL 覆盖 Schema 检索、SQL 生成、AST 校验、只读执行与失败修正。

**5. 安全防护与系统韧性**

输入、输出、工具调用三道护栏覆盖 PII 脱敏、毒性拦截、幻觉检测与红队测试。通过四路熔断、Redis 租约、Checkpoint 与 OpenTelemetry 提升外部依赖稳定性和执行可恢复性。