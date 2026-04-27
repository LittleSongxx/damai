# damai-ai + damai-pro 本地一体化（Docker）

## 1) 准备环境变量

1. 在 `damai-pro` 目录准备基础设施变量：

```powershell
Copy-Item .env.example .env -ErrorAction SilentlyContinue
```

2. 在 `damai-ai` 目录准备 AI 变量：

```powershell
Copy-Item .env.example .env -ErrorAction SilentlyContinue
Copy-Item .\vue\.env.example .\vue\.env -ErrorAction SilentlyContinue
```

3. 打开 `damai-ai/.env`，至少填写以下 API Key：
- `DAMAI_AI_ALIBABA_API_KEY=...`
- `DAMAI_AI_DEEPSEEK_API_KEY=...`

> 如果你暂时只用 Ollama，可以把上面两个 Key 留空，并确保 `DAMAI_AI_OLLAMA_BASE_URL` 可访问。

## 2) 启动 Docker 依赖

在 `damai-pro` 目录执行：

```powershell
docker compose --profile ai up -d
```

该命令会启动：MySQL、Redis、Nacos、Kafka、Elasticsearch、Seata、Sentinel，以及 AI 需要的 Prometheus、Ollama。

## 3) 初始化数据库（包含 damai-ai）

在 `damai-pro` 目录执行：

```powershell
.\scripts\init-databases.ps1
```

如需只初始化 damai-pro，不导入 damai-ai SQL：

```powershell
.\scripts\init-databases.ps1 -SkipDamaiAiSql
```

## 4) 启动应用

1. 启动 `damai-pro` 后端服务（建议按 `docs/local-dev.md` 的顺序）。
2. 启动 `damai-ai`：

```powershell
Get-Content .\damai-ai\.env | ForEach-Object {
  if ($_ -match '^(?!#)([^=]+)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
  }
}

mvn -f .\damai-ai\pom.xml -pl damai-core-service spring-boot:run
mvn -f .\damai-ai\pom.xml -pl damai-mcp-server\damai-mcp-log-service spring-boot:run
mvn -f .\damai-ai\pom.xml -pl damai-mcp-server\damai-mcp-metrics-service spring-boot:run
```

3. 启动 `damai-ai/vue` 前端：

```powershell
cd .\damai-ai\vue
npm install
npm run dev
```

## 5) 关键地址

- damai-pro 网关：`http://127.0.0.1:6085`
- damai-ai 核心服务：`http://127.0.0.1:6089`
- damai-ai 前端：`http://127.0.0.1:5173`
- MCP 日志服务：`http://127.0.0.1:8085/sse`
- MCP 指标服务：`http://127.0.0.1:8086/sse`
- Prometheus：`http://127.0.0.1:9090`

## 6) 常见问题

- `401/鉴权失败`：确认 `DAMAI_AI_*_API_KEY` 已填且无多余空格。
- `AI 无法下单`：确认 `DAMAI_AI_*_URL` 指向 `damai-pro` 网关地址。
- `指标查询为空`：确认 `docker compose --profile ai ps` 中 `prometheus` 为 `Up`。
- `ES 查询失败`：确认 `DAMAI_ES_ADDR`、`DAMAI_ES_USERNAME`、`DAMAI_ES_PASSWORD` 与 `damai-pro/.env` 一致。

