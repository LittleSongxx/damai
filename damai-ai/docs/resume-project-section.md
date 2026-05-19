# damai-ai — 票务智能助手平台

> **Author**: Song

## 项目简介

面向票务场景的 AI 智能助手平台，围绕意图路由 → Skill 执行 → SSE 流式输出的核心链路，统一承载 Hybrid RAG 知识问答、LLM Tool Calling 购票、联网搜索、NL2SQL 运维查询、MCP 日志/指标监控五大能力。基于 Spring Boot 3.5 + Spring AI 1.0，后端 340+ 源文件。

## 技术栈

Spring Boot 3.5、Spring AI 1.0（ChatClient / Tool Calling / MCP / RAG）、MyBatis-Plus、Qdrant + Elasticsearch 双路检索、RabbitMQ、Redis + Redisson（分布式锁 / 滑动窗口限流 / 租约管理）、Resilience4j、OpenTelemetry、JSQLParser、Caffeine、Vue 3 + SSE

## 职责与技术实现

1. 设计五域统一的 Skill 执行引擎，将 RAG、购票、搜索、NL2SQL、MCP 五类异构能力抽象为同一套 SkillExecutor 流水线（声明式描述符 → Schema 校验 → 工具白名单沙箱 → 策略守卫），并通过 Redisson 分布式读写锁（AOP 注解驱动）与对话级租约管理（Lua 原子操作 + 守护线程自动续期）解决并发下同一会话重复执行问题，实现多能力模块的统一治理与安全调度。

2. 构建 Hybrid RAG + CRAG 纠正式检索管线：首轮检索中，LLM 查询改写后进行多路并行召回——Qdrant 稠密向量检索、ES BM25 稀疏检索、HyDE 假想文档检索，经证据门槛过滤与 RRF 融合后，由 qwen3-rerank 交叉编码器精排与 LLM 上下文压缩得到初步结果；随后通过五维置信度评估（向量分 / 稀疏分 / 双路重叠 / 源数量 / 来源多样性）驱动 CRAG 分级纠正——模糊时扩展 3 倍 topK 并拆分子问题分别检索，不可靠时由 LLM 根据缺失信息改写查询后重新检索，纠正后仍不可靠则拒答。管线中引入 Caffeine 本地缓存 Embedding 向量、Redis 缓存检索结果并基于 Pub/Sub 跨节点失效同步，从机制上抑制幻觉并降低重复检索开销。

3. 搭建 NL2SQL 安全执行链路，引入 JSQLParser 对 LLM 生成的 SQL 做 AST 级七层校验（SELECT-only、白名单表名、禁止敏感字段与危险函数、强制 LIMIT、拒绝多语句与注释注入），搭配执行失败的异常自动分类与 LLM 自修复重试，赋予自然语言查数能力的同时确保数据库只读安全。

4. 建立异步记忆压缩与跨会话画像体系：每次 Run 完成后由 RabbitMQ 异步驱动 LLM 将多轮对话压缩为结构化摘要（摘要 / 目标 / 稳定事实 / 待解决问题 / 检索提示词），叠加指数时间衰减（半衰期 70 天）+ 90 天 TTL 淘汰，同步提取用户偏好画像跨会话注入 Prompt，使长期对话体验不随轮次增长而退化。

5. 建设多层韧性防线与全链路可观测体系：为 LLM/Qdrant/ES/联网搜索配置独立 Resilience4j 熔断器，辅以规则驱动的模型路由（简单查询走廉价模型、复杂查询走主力模型）与主备自动切换；Redis 滑动窗口限流（Sorted Set + Lua 原子化执行）按端点/用户/IP 分级控流；线程池队列负载达 80% 阈值时自动溢出到 RabbitMQ 异步消费避免请求丢弃。可观测方面，通过 Spring AI Advisor 自动采集每次 LLM 调用的模型、延迟、Token 消耗与成本，结合 OpenTelemetry OTLP 分布式 Trace 与 Micrometer 业务指标（缓存命中率 / 熔断降级 / Skill 调用量），实现对话级成本核算与性能瓶颈定位。
