# damai-pro 本地开发环境

## 环境要求

- Docker（`docker compose version` 可用）
- JDK 17+
- Maven 3.9+
- Node.js 与 npm

> 如需同时联调 `damai-ai`，见 [`damai-ai-integration.md`](damai-ai-integration.md)。

## 基础设施

`docker-compose.yml` 统一管理本地依赖（`name: damai`）：

| 服务 | 端口映射 |
| --- | --- |
| MySQL | `13306 → 3306` |
| Redis | `16379 → 6379` |
| Nacos | `18848 → 8848` |
| RabbitMQ | `5672` / 管理端 `15672` |
| Elasticsearch | `19200 → 9200` |
| Seata | `8091` |
| Sentinel | `8082` |

所有端口由 `.env` 统一管理，修改后重启即可。

### 启动依赖

```bash
docker compose up -d
# 包含 AI 相关组件 (Prometheus, Qdrant, Ollama):
docker compose --profile ai up -d
```

### 环境变量

后端服务支持以下环境变量覆盖：

- `DAMAI_MYSQL_HOST` / `DAMAI_MYSQL_PORT` / `DAMAI_MYSQL_USERNAME` / `DAMAI_MYSQL_PASSWORD`
- `DAMAI_REDIS_HOST` / `DAMAI_REDIS_PORT`
- `DAMAI_NACOS_ADDR` / `DAMAI_NACOS_USERNAME` / `DAMAI_NACOS_PASSWORD`
- `DAMAI_RABBITMQ_HOST` / `DAMAI_RABBITMQ_AMQP_PORT` / `DAMAI_RABBITMQ_USERNAME` / `DAMAI_RABBITMQ_PASSWORD`
- `DAMAI_ES_ADDR` / `DAMAI_ES_USERNAME` / `DAMAI_ES_PASSWORD`
- `DAMAI_SEATA_SERVER`
- `DAMAI_SENTINEL_DASHBOARD`

### 分库分表配置生成

MySQL 非默认端口时需生成本地 ShardingSphere override YAML：

```bash
# PowerShell:
.\scripts\render-local-shardingsphere-configs.ps1 -MySqlHost 127.0.0.1 -MySqlPort 13306
```

生成文件位于 `local-config/` 目录。

### 数据库初始化

```bash
# PowerShell:
.\scripts\init-databases.ps1

# 跳过 damai-ai SQL:
.\scripts\init-databases.ps1 -SkipDamaiAiSql
```

### 健康检查

```bash
.\scripts\check-local-env.ps1
```

### 完整验证

```bash
.\scripts\validate-local-startup.ps1
# 仅验证，不重启 Docker 和不重新构建:
.\scripts\validate-local-startup.ps1 -SkipDockerUp -SkipBuild
```

## 后端启动顺序

```mermaid
graph LR
    A[admin] --> B[base-data] --> C[customize] --> D[user]
    D --> E[program] --> F[pay] --> G[order] --> H[migrate] --> I[gateway]
```

仓库提供 `.run/` 共享 IDEA 运行配置：
- `damai-infra` — Docker 基础设施
- `damai-backend-all` — 全部后端服务
- `vue3-dev` — 前端开发服务器

首次使用建议：`damai-infra` → `damai-backend-all` → `vue3-dev`。

## 前端

```bash
cd vue3
npm install
npm run dev -- --host 127.0.0.1 --port 15173 --strictPort
```

代理目标：`http://127.0.0.1:6085`

## 常用地址

| 服务 | 地址 |
| --- | --- |
| 网关 | `http://127.0.0.1:6085` |
| 前端 | `http://127.0.0.1:15173` |
| Nacos | `http://127.0.0.1:18848/nacos` |
| Sentinel | `http://127.0.0.1:8082` |
| Admin | `http://127.0.0.1:10082` |
| Elasticsearch | `http://127.0.0.1:19200` |

## 手工验证

直连服务健康检查：

```bash
curl http://127.0.0.1:6083/actuator/health
```

业务接口验证（直连，不经网关签名）：

```bash
curl -X POST http://127.0.0.1:6083/channel/data/getByCode \
  -H "Content-Type: application/json" \
  -d '{"code":"0001"}'
```

> 网关链路经过 `RequestValidationFilter` 需 RSA 签名，建议通过前端或 `validate-local-startup` 脚本验证。
