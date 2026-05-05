# damai 工作区

`damai` 是一个面向高并发票务业务与 AI 助手联动场景的 Java 全栈实践工作区。仓库以 Maven 聚合工程组织后端，以 Vue 前端承载用户端、AI 端和管理端页面，重点覆盖票务交易链路、分库分表、缓存与消息一致性、可观测性、RAG、MCP 和 AI 业务工具调用。

## 项目组成

| 路径 | 定位 |
| --- | --- |
| `damai-pro` | 高并发票务后端与用户端前端，包含票务、订单、支付、用户、网关、后台监控等微服务 |
| `damai-ai` | AI 助手后端与 AI 前端，负责业务问答、规则问答、运维诊断、RAG、MCP 工具接入 |
| `damai-pro-front-manage` | 后台管理前端，基于 `vue-vben-admin` monorepo 改造，面向运营与管理场景 |
| `scripts` | 工作区级别启动脚本，目前提供 `damai-stack.sh` 一键管理本地全栈 |

## 架构定位

- **业务系统**：`damai-pro` 承载节目浏览、票档库存、座位锁定、订单创建、支付、用户和后台管理能力。
- **智能助手**：`damai-ai` 在传统票务系统之上提供自然语言交互，调用节目、票档、下单等业务接口，也能通过 RAG 回答规则问题。
- **运维辅助**：`damai-ai` 通过 MCP 日志服务和指标服务接入 Elasticsearch 与 Prometheus，为本地服务诊断提供证据链。
- **基础设施**：`damai-pro/docker-compose.yml` 统一编排 MySQL、Redis、Nacos、RabbitMQ、Elasticsearch、Seata、Sentinel、Prometheus、Qdrant、Ollama 等依赖。

## 技术栈概览

| 分类 | 主要技术 |
| --- | --- |
| 后端基础 | Java 17、Maven、Spring Boot、Spring Cloud、Spring Cloud Alibaba |
| 微服务治理 | Nacos、OpenFeign、Gateway、Sentinel、Seata、Spring Boot Admin |
| 数据与缓存 | MySQL、Redis、MyBatis Plus、ShardingSphere、Redisson、Elasticsearch |
| 消息与异步 | RabbitMQ、延迟/确认/消费补偿、业务审计消息 |
| AI 能力 | Spring AI、OpenAI 兼容模型、DeepSeek、Ollama、RAG、VectorStore、MCP |
| 前端 | Vue 3、Vite、Element Plus、Naive UI、Pinia、vue-vben-admin、pnpm/turbo |
| 可观测性 | Prometheus、Actuator、Easy-ES、MCP metrics/log 服务 |

## 快速启动

### 1. 准备环境

建议环境：

- JDK 17+
- Maven 3.9+
- Docker 与 Docker Compose
- Node.js 与 npm
- `pnpm` 仅在启动 `damai-pro-front-manage` 时需要

准备环境变量：

```bash
cp damai-pro/.env.example damai-pro/.env
cp damai-ai/.env.example damai-ai/.env
```

如果需要使用云端模型，请在 `damai-ai/.env` 中填写对应 API Key，避免把密钥提交到仓库。

### 2. 一键启动全栈

```bash
bash scripts/damai-stack.sh start
```

常用参数：

```bash
bash scripts/damai-stack.sh start --skip-build
bash scripts/damai-stack.sh start --skip-db-init
bash scripts/damai-stack.sh start --skip-frontend
bash scripts/damai-stack.sh status
bash scripts/damai-stack.sh stop
```

脚本会读取 `damai-pro/.env` 与 `damai-ai/.env`，启动 Docker 依赖、初始化数据库、构建后端服务、启动 Spring Boot 服务与两个前端。

### 3. 常用地址

| 服务 | 地址 |
| --- | --- |
| damai-pro 用户端 | `http://127.0.0.1:15173` |
| damai-ai 前端 | `http://127.0.0.1:15174` |
| damai-pro 网关 | `http://127.0.0.1:6085` |
| damai-ai 核心服务 | `http://127.0.0.1:6089` |
| Spring Boot Admin | `http://127.0.0.1:10082` |
| Nacos | `http://127.0.0.1:18848/nacos` |
| Sentinel | `http://127.0.0.1:8082` |
| Prometheus | `http://127.0.0.1:9090` |
| Elasticsearch | `http://127.0.0.1:19200` |
| Qdrant | `http://127.0.0.1:16333` |
| Ollama | `http://127.0.0.1:11434` |

## 推荐阅读顺序

1. [`damai-pro/README.md`](damai-pro/README.md)：理解高并发票务后端、服务拆分和本地启动。
2. [`damai-ai/README.md`](damai-ai/README.md)：理解 AI 助手、RAG、MCP 和 `damai-pro` 联动方式。
3. [`damai-pro/docs/local-dev.md`](damai-pro/docs/local-dev.md)：查看更细的本地开发与验证说明。
4. [`damai-pro/docs/damai-ai-integration.md`](damai-pro/docs/damai-ai-integration.md)：查看 AI 与票务系统一体化联调步骤。
5. [`damai-pro-front-manage/README.md`](damai-pro-front-manage/README.md)：查看后台管理前端启动方式。

## 排障提示

- **端口冲突**：优先修改 `damai-pro/.env` 中的 `DAMAI_*_PORT` 与前端端口变量，再重新执行启动脚本。
- **AI 鉴权失败**：检查 `damai-ai/.env` 中模型 API Key 是否填写，且没有多余空格。
- **数据库未初始化**：执行 `bash scripts/damai-stack.sh start --force-db-init` 重新导入 SQL。
- **前端请求失败**：确认 `damai-pro` 网关 `6085`、`damai-ai` 核心服务 `6089` 已健康。
- **MCP 证据为空**：确认 Prometheus、Elasticsearch、MCP log/metrics 服务已启动。

## 文档维护原则

- 根 README 只描述工作区全局视角与启动入口。
- 子项目 README 负责各自架构、模块、命令和排障。
- 端口、服务名、环境变量应以 `.env.example`、`application.yml`、`application.yaml`、`package.json` 和启动脚本为准。
