# 智能票务 Agent 平台｜AI 应用开发 / 后端开发

## 项目简介

围绕演出票务业务构建一体化 AI Agent 平台，覆盖智能客服、AI 辅助购票和管理员自然语言问数。项目将大模型对话、工具调用、记忆、审批审计与节目、库存、订单链路打通，重点解决票务场景下回答延迟、执行可控、交易一致性和数据安全问题。

## 技术栈

Java / Spring Boot / Spring AI / Spring Cloud Alibaba / MySQL / Redis / RabbitMQ / Qdrant / Elasticsearch / ShardingSphere / Seata / Sentinel / JSQLParser / MCP

## 核心亮点

1. **智能客服分层闭环：** 设计“热问/FAQ 直答 - RAG 知识问答 - Agent/人工升级”的分层客服流程，对退款、实名入场、票档、售后等高频问题优先匹配标准答案，并结合情绪识别、工单流转和 SLA 升级机制，兼顾响应效率与售后风险控制。

2. **客服 RAG 全链路：** 建设面向票务规则的 RAG 管线，覆盖 FAQ 假设问生成、Query Rewrite、实体扩展、稠密/稀疏/HyDE 多通道检索、RRF 融合、证据门控、CRAG 纠错和引用生成；在客服黄金集与在线 trace 中设定 Recall@5 ≥ 90%、Context Precision ≥ 80%、Faithfulness ≥ 90%、Unsupported Claim Rate ≤ 5%、P95 检索延迟 ≤ 800ms、P95 TTFT ≤ 2s 的质量门槛。

3. **AI 购票可控执行：** 实现 AI 辅助购票流程，通过对话状态机补齐城市、艺人、日期、票档、购票人、手机号等槽位，调用节目搜索、详情查询和购票预览工具生成 Action Preview；下单前强制用户确认，并保存业务快照、风险校验和幂等键，避免模型越权交易。

4. **交易一致性保障：** 将 AI 购票接入真实票务交易链路，设计 AI Reservation 预留、确认、释放三段式 Saga；库存侧用 Redis Lua 原子扣减余票并迁移座位状态，订单侧用 requestHash、状态 CAS、TTL 释放和 UNKNOWN 状态查询收敛重复提交、超时和结果不确定问题。

5. **NL2SQL 安全问数：** 建设受控 NL2SQL 能力，基于语义目录管理数据集、视图、字段、指标和业务术语，结合 Schema Linking 缓存与多轮上下文处理追问；生成 SQL 后用 JSQLParser 做 AST 校验，并通过表/字段白名单、只读数据源、EXPLAIN 成本守卫、超时、限行和脱敏保障数据安全。
