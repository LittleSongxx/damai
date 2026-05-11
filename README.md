# damai — 票务系统全栈工程

基于 Spring Cloud Alibaba + Spring AI 的全栈票务系统，包含高并发微服务后端、AI 智能助手和多端前端。

> **Author**: Song <2212565023@qq.com> · [GitHub](https://github.com/LittleSongxx/damai)

---

## 架构总览

```mermaid
graph TB
    subgraph Clients["客户端"]
        ProVue[用户端 Vue 3<br/>:15173]
        AIVue[AI 助手 Vue 3<br/>:15174]
    end

    subgraph AI["damai-ai · 智能助手"]
        AICore[Core Service :6089<br/>Skill 引擎 / RAG / Tool Calling]
        MCPLog[日志 MCP :8085]
        MCPMetrics[指标 MCP :8086]
    end

    subgraph Pro["damai-pro · 票务微服务"]
        Gateway[Gateway :6085]
        UserSvc[用户服务]
        ProgramSvc[节目服务]
        OrderSvc[订单服务]
        PaySvc[支付服务]
        Others[基础数据 / 采集 / 迁移 / Admin]
    end

    subgraph Infra["基础设施 (Docker Compose)"]
        MySQL[(MySQL)]
        Redis[(Redis)]
        RabbitMQ[(RabbitMQ)]
        Nacos[Nacos]
        ES[(Elasticsearch)]
        Qdrant[(Qdrant)]
        Prometheus[(Prometheus)]
    end

    ProVue --> Gateway
    AIVue --> AICore
    AICore --> Gateway
    AICore --> MCPLog
    AICore --> MCPMetrics
    Gateway --> Pro
    Pro --> Infra
    AICore --> Qdrant
    AICore --> ES
    MCPLog --> ES
    MCPMetrics --> Prometheus
```

## 项目结构

```
damai/
├── damai-pro/          # 高并发票务微服务系统
│   ├── damai-server/   #   9 个可启动微服务
│   ├── damai-*-framework/  # 公共框架模块
│   ├── vue3/           #   用户端前端
│   └── docker-compose.yml
├── damai-ai/           # AI 智能助手平台 (全面重构)
│   ├── damai-core-service/   # AI 核心服务
│   ├── damai-mcp-server/     # MCP Server (日志 + 指标)
│   └── vue/            #   AI 前端
├── scripts/            # 工作区级启动脚本
└── pom.xml             # Maven 聚合
```

## 子项目

### [damai-pro](damai-pro/) — 高并发票务微服务

基于 Spring Cloud Alibaba 的微服务架构，覆盖：
- 9 个业务微服务（用户、节目、订单、支付、网关等）
- ShardingSphere 分库分表 + 基因法路由
- Redis 库存缓存 + Redisson 分布式锁
- RabbitMQ 异步订单 + Seata 分布式事务
- 多版本下单链路演进 + 中间件故障兜底

**技术栈**: Java 17 · Spring Boot 3.3 · Spring Cloud 2023 · Nacos · Sentinel · ShardingSphere · MyBatis Plus

### [damai-ai](damai-ai/) — 票务智能助手平台

基于 Spring AI 全面重构的智能助手平台（281+ 源文件，91 单元测试），五大核心能力：
- **Hybrid RAG** — Corrective RAG 流水线，Qdrant + ES BM25 + RRF 融合 + Rerank
- **Tool Calling** — LLM 自主编排购票工具，人在环审批
- **联网搜索** — Tavily/博查联网证据 + LLM 生成
- **NL2SQL** — 自然语言运维查询，AST 七层安全校验
- **MCP 监控** — 日志/指标 MCP Server，LLM 诊断建议

**技术栈**: Java 17 · Spring Boot 3.5 · Spring AI 1.0 · Qdrant · Vue 3 · Naive UI

## 快速启动

### 一键启动

```bash
bash scripts/damai-stack.sh start
```

自动完成：Docker 基础设施 → 数据库初始化 → Maven 构建 → 后端服务启动 → 前端启动。

### 常用命令

```bash
bash scripts/damai-stack.sh status    # 查看状态
bash scripts/damai-stack.sh stop      # 停止全部
bash scripts/damai-stack.sh start --skip-build      # 跳过构建
bash scripts/damai-stack.sh start --skip-frontend   # 跳过前端
```

### 手动启动

参考各子项目文档：
- [damai-pro 本地开发](damai-pro/docs/local-dev.md)
- [damai-ai README](damai-ai/README.md)
- [联调指南](damai-pro/docs/damai-ai-integration.md)

## 服务地址一览

| 服务 | 地址 |
| --- | --- |
| damai-pro 用户端 | `http://127.0.0.1:15173` |
| damai-ai 前端 | `http://127.0.0.1:15174` |
| API 网关 | `http://127.0.0.1:6085` |
| AI 核心服务 | `http://127.0.0.1:6089` |
| Nacos | `http://127.0.0.1:18848/nacos` |
| Prometheus | `http://127.0.0.1:9090` |
| Elasticsearch | `http://127.0.0.1:19200` |
| Spring Boot Admin | `http://127.0.0.1:10082` |

## 环境要求

- Docker（`docker compose` 可用）
- JDK 17+
- Maven 3.9+
- Node.js 24+ 与 npm

## 文档索引

| 文档 | 说明 |
| --- | --- |
| [damai-pro/README.md](damai-pro/README.md) | 票务微服务架构与模块说明 |
| [damai-ai/README.md](damai-ai/README.md) | AI 智能助手架构与能力说明 |
| [damai-pro/docs/local-dev.md](damai-pro/docs/local-dev.md) | 本地开发环境搭建 |
| [damai-pro/docs/damai-ai-integration.md](damai-pro/docs/damai-ai-integration.md) | AI + Pro 联调指南 |
| [damai-ai/docs/resume-project-section.md](damai-ai/docs/resume-project-section.md) | AI 项目技术亮点 |

## License

Apache License 2.0
