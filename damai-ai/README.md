# damai-ai

`damai-ai` 是大麦体系中的智能助手工程，基于 Spring AI 将大模型、RAG、MCP、业务工具调用和传统票务系统连接起来。它不单独替代 `damai-pro`，而是在 `damai-pro` 的节目、用户、订单、日志和指标数据之上提供自然语言交互能力。

## 项目定位

`damai-ai` 重点解决三类问题：

- **业务助手**：理解用户购票意图，调用节目检索、节目详情、票档查询、观演人和下单准备等工具。
- **规则助手**：基于 FAQ、Markdown、PDF 等知识内容做 RAG 检索，回答购票、退票、入场等规则问题。
- **运维助手**：通过 MCP 查询日志与 Prometheus 指标，辅助定位服务异常、接口失败和资源问题。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 后端基础 | Java 17、Maven、Spring Boot 3.5、MyBatis Plus |
| AI 框架 | Spring AI 1.0、ChatClient、Advisor、Tool Calling、Structured Output |
| 模型接入 | OpenAI 兼容接口、阿里百炼、DeepSeek、Ollama |
| RAG | Spring AI RAG、Markdown/PDF Reader、VectorStore、Qdrant |
| 记忆与会话 | JDBC Chat Memory、自定义会话、运行事件、用户画像 |
| 工具调用 | 业务工具服务、MCP Client、MCP Server WebFlux SSE |
| 数据与消息 | MySQL、RabbitMQ、Elasticsearch、Easy-ES |
| 可观测性 | Actuator、Prometheus、Token/耗时统计、日志与指标 MCP |
| 前端 | Vue 3、Vite、Naive UI、Pinia、Markdown 渲染 |

## 模块结构

| 模块 | 说明 |
| --- | --- |
| `damai-core-service` | AI 核心服务，负责会话、路由、技能执行、RAG、业务工具、记忆和可观测性 |
| `damai-mcp-server/damai-mcp-log-service` | 日志 MCP 服务，通过 SSE 暴露日志检索工具，依赖 Elasticsearch |
| `damai-mcp-server/damai-mcp-metrics-service` | 指标 MCP 服务，通过 SSE 暴露 Prometheus 指标查询工具 |
| `vue` | AI 助手前端，负责对话、运行事件展示、Markdown 渲染和业务跳转 |
| `sql` | `damai_ai` 数据库初始化脚本 |

## 服务与端口

| 服务 | 默认端口 | 入口类 |
| --- | --- | --- |
| `damai-core-service` | `6089` | `org.javaup.ai.DaMaiAiCoreApplication` |
| `damai-mcp-log-service` | `8085` | `org.javaup.mcp.DaMaiMcpLogApplication` |
| `damai-mcp-metrics-service` | `8086` | `org.javaup.mcp.DaMaiMcpMetricsApplication` |
| `damai-ai/vue` | `15174` | Vite 开发服务 |

## 环境变量

复制模板：

```bash
cp damai-ai/.env.example damai-ai/.env
```

重要变量：

| 变量 | 说明 |
| --- | --- |
| `DAMAI_AI_PORT` | AI 核心服务端口，默认 `6089` |
| `DAMAI_AI_MYSQL_URL` | `damai_ai` 数据库连接 |
| `DAMAI_AI_ALIBABA_API_KEY` | 阿里百炼/OpenAI 兼容模型 API Key |
| `DAMAI_AI_DEEPSEEK_API_KEY` | DeepSeek API Key |
| `DAMAI_AI_OLLAMA_BASE_URL` | 本地 Ollama 地址 |
| `DAMAI_AI_MCP_LOG_URL` | 日志 MCP SSE 地址 |
| `DAMAI_AI_MCP_METRICS_URL` | 指标 MCP SSE 地址 |
| `DAMAI_AI_QDRANT_URL` | Qdrant 向量库地址 |
| `DAMAI_AI_*_URL` | 指向 `damai-pro` 网关的业务接口地址 |
| `VITE_DAMAI_AI_PROXY_TARGET` | AI 前端代理目标，默认核心服务 `6089` |

API Key 只应写入本地 `.env` 或 IDE 运行配置，不要硬编码到源码或提交到仓库。

## 快速启动

### 推荐：工作区一键启动

在工作区根目录执行：

```bash
bash scripts/damai-stack.sh start
```

该脚本会同时启动 `damai-pro`、`damai-ai`、MCP 服务、用户端前端和 AI 前端，并自动检查 `damai_ai` 所需表结构。

### 手动启动后端

先确保 `damai-pro` 的 Docker 依赖和数据库已启动，再执行：

```bash
mvn -f damai-ai/pom.xml -pl damai-core-service spring-boot:run
mvn -f damai-ai/pom.xml -pl damai-mcp-server/damai-mcp-log-service spring-boot:run
mvn -f damai-ai/pom.xml -pl damai-mcp-server/damai-mcp-metrics-service spring-boot:run
```

### 手动启动前端

```bash
cd damai-ai/vue
npm install
npm run dev -- --host 127.0.0.1 --port 15174 --strictPort
```

## 核心架构

### 统一助手运行流

1. 前端创建一次 Assistant Run。
2. `damai-core-service` 记录会话、消息和运行状态。
3. 路由器判断用户意图：业务、知识、运维或通用聊天。
4. 执行器加载用户上下文、记忆、画像和权限。
5. 对应技能执行工具调用、RAG 检索、MCP 查询或大模型回答。
6. 运行事件流式返回前端，同时写入审计、记忆和可观测数据。

### 业务助手

业务助手通过 `BusinessToolService` 调用传统票务能力，例如节目推荐、节目搜索、节目详情、票档查询和下单准备。它负责自然语言理解与工具编排，真正的库存、座位、订单和支付一致性仍由 `damai-pro` 负责。

### 知识助手

知识助手基于 RAG 流程工作：

1. 加载规则文档。
2. 写入向量库。
3. 对用户问题做检索规划。
4. 评估检索证据置信度。
5. 生成带依据的回答。
6. 低置信度时拒绝编造确定答案。

### 运维助手

运维助手通过 MCP 接入外部能力：

- `damai-mcp-log-service` 查询 Elasticsearch 中的日志和 API 采集数据。
- `damai-mcp-metrics-service` 查询 Prometheus 中的 JVM、内存、线程和接口指标。
- 核心服务将证据汇总给模型，由模型输出诊断建议。

## 与 damai-pro 的关系

`damai-pro` 是业务事实源，`damai-ai` 是智能交互层。AI 能够帮助用户更自然地完成查询和购票，但不会绕过 `damai-pro` 的网关、登录态、库存、订单和支付约束。

联调前请确认：

- `damai-gateway-service` 可访问：`http://127.0.0.1:6085`
- `damai-pro` 用户端可访问：`http://127.0.0.1:15173`
- `damai-ai` 前端可访问：`http://127.0.0.1:15174`
- `damai-ai/.env` 中业务接口地址指向当前 `damai-pro` 网关。

## 常见问题

- **模型鉴权失败**：检查 `DAMAI_AI_ALIBABA_API_KEY`、`DAMAI_AI_DEEPSEEK_API_KEY` 是否填写正确。
- **本地模型不可用**：检查 Ollama 是否启动，模型名是否与 `DAMAI_AI_OLLAMA_CHAT_MODEL` 一致。
- **RAG 返回为空**：检查 Qdrant 地址、集合名、文档加载路径和向量维度配置。
- **MCP 连接失败**：确认 `8085`、`8086` 服务已启动，SSE 地址配置正确。
- **AI 无法调用业务接口**：确认 `damai-pro` 网关、登录态、用户端端口和 `DAMAI_AI_*_URL` 是否一致。
- **日志或指标为空**：确认 Elasticsearch、Prometheus 和对应采集链路已有数据。

## 相关文档

- [`../damai-pro/docs/damai-ai-integration.md`](../damai-pro/docs/damai-ai-integration.md)：AI 与票务系统一体化联调。
- [`vue/README.md`](vue/README.md)：AI 前端说明。
- [`../README.md`](../README.md)：工作区总览。

