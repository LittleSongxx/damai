# damai-pro 本地开发环境

## 环境要求
- Docker Desktop，且 `docker compose version` 可用
- JDK 17+，仓库当前已用 Java 21 编译验证
- Maven 3.9+
- Node.js 24+ 与 npm

如果你要把 `damai-ai` 一起联调，直接看 `docs/damai-ai-integration.md`。

## 基础设施
根目录 `docker-compose.yml` 已固定为 `name: damai`，用于统一管理以下依赖：
- MySQL `13306 -> 3306`
- Redis `16379 -> 6379`
- Nacos `18848 -> 8848`
- Kafka `19092 -> 9092`
- Elasticsearch `19200 -> 9200`
- Seata `8091`
- Sentinel Dashboard `8082`

当前工作区已经在根目录 `.env` 中写入了这台机器实际可用的端口，`docker compose`、验证脚本和共享运行配置都已经按这套端口对齐。
如果你后面想切回标准端口或换成别的端口，直接修改 `.env` 即可。

启动依赖：

```powershell
docker compose up -d
```

如果你想临时覆盖 `.env` 里的端口，仍然可以先在当前终端里设置 `DAMAI_*` 环境变量，再启动 Docker 和后端服务，例如：

```powershell
$env:DAMAI_MYSQL_PORT = "13306"
$env:DAMAI_NACOS_PORT = "18848"
$env:DAMAI_NACOS_GRPC_PORT = "19848"
$env:DAMAI_NACOS_RAFT_PORT = "19849"
$env:DAMAI_REDIS_PORT = "16379"
$env:DAMAI_KAFKA_PORT = "19092"
$env:DAMAI_ES_PORT = "19200"
$env:DAMAI_MYSQL_HOST = "127.0.0.1"
$env:DAMAI_NACOS_ADDR = "127.0.0.1:18848"
$env:DAMAI_REDIS_HOST = "127.0.0.1"
$env:DAMAI_KAFKA_BOOTSTRAP_SERVERS = "127.0.0.1:19092"
$env:DAMAI_ES_ADDR = "127.0.0.1:19200"
```

后端服务也已经支持通过环境变量覆盖本地依赖地址：
- `DAMAI_MYSQL_HOST` / `DAMAI_MYSQL_PORT` / `DAMAI_MYSQL_USERNAME` / `DAMAI_MYSQL_PASSWORD`
- `DAMAI_REDIS_HOST` / `DAMAI_REDIS_PORT`
- `DAMAI_NACOS_ADDR` / `DAMAI_NACOS_USERNAME` / `DAMAI_NACOS_PASSWORD`
- `DAMAI_KAFKA_BOOTSTRAP_SERVERS`
- `DAMAI_ES_ADDR` / `DAMAI_ES_USERNAME` / `DAMAI_ES_PASSWORD`
- `DAMAI_SEATA_SERVER`
- `DAMAI_SENTINEL_DASHBOARD`

仓库脚本会自动读取根目录 `.env`。如果 MySQL 主机、端口或账号不是默认值，分库分表服务还需要生成一套本地 override YAML：

```powershell
.\scripts\render-local-shardingsphere-configs.ps1 -MySqlHost 127.0.0.1 -MySqlPort 13306
. .\local-config\damai-sharding-env.ps1
```

这会生成：
- `local-config/shardingsphere-user-local.generated.yaml`
- `local-config/shardingsphere-pay-local.generated.yaml`
- `local-config/shardingsphere-order-local.generated.yaml`
- `local-config/shardingsphere-program-local.generated.yaml`
- `local-config/shardingsphere-migrate-local.generated.yaml`
- `local-config/damai-sharding-env.ps1`

初始化数据库与种子数据：

```powershell
.\scripts\init-databases.ps1
```

检查依赖健康与数据库初始化结果：

```powershell
.\scripts\check-local-env.ps1
```

一键做完整本地联调验证：

```powershell
.\scripts\validate-local-startup.ps1
```

如果你希望把数据库中的节目海报外链迁移到本地静态资源，执行：

```powershell
.\scripts\migrate-poster-images.ps1
```

这个脚本会：
- 下载 `damai_program_0.d_program_0` 和 `damai_program_1.d_program_1` 中的 `item_picture`
- 落盘到 `vue3/public/posters/`
- 把数据库里的 `item_picture` 更新成 `/posters/...`
- 生成映射文件 `local-config/poster-image-map.csv`
- 生成回滚 SQL `local-config/poster-image-restore.sql`

如果你明确希望重新灌入全部 SQL，可以执行：

```powershell
.\scripts\validate-local-startup.ps1 -ForceDatabaseInit
```

## 后端启动顺序
推荐先启动基础设施，再按下面顺序启动服务：
1. `damai-admin-service`
2. `damai-base-data-service`
3. `damai-customize-service`
4. `damai-user-service`
5. `damai-program-service`
6. `damai-pay-service`
7. `damai-order-service`
8. `damai-migrate-service`
9. `damai-gateway-service`

仓库已提供 `.run/` 共享运行配置：
- `damai-infra`
- 各个 Spring Boot 服务
- `vue3-dev`
- `damai-backend-all`
- `damai-backend-and-vue3`

首次使用建议：
1. 运行 `damai-infra`
2. 运行 `damai-backend-all`
3. 运行 `vue3-dev`

当前 `.run/` 里的 Spring Boot 配置已经按这台机器的本地端口和分片 JDBC 文件预先填好环境变量，打开 IDEA 后可以直接使用。
如果你后面手动改了 `.env` 里的 MySQL 主机、端口或账号，请重新执行 `.\scripts\render-local-shardingsphere-configs.ps1`，让 `local-config/` 下的生成文件同步更新。

## 前端
安装依赖：

```powershell
cd .\vue3
npm install
```

启动：

```powershell
npm run dev
```

前端开发代理目标已经固定为 `http://127.0.0.1:6085`。

## 常用地址
- 网关: `http://127.0.0.1:6085`
- 前端: `http://127.0.0.1:5173`
- Nacos: `http://127.0.0.1:18848/nacos`
- Sentinel: `http://127.0.0.1:8082`
- Admin: `http://127.0.0.1:10082`
- Elasticsearch: `http://127.0.0.1:19200`

## 手工验证建议
- 直接服务健康检查：`http://127.0.0.1:6083/actuator/health`
- 直接服务业务检查，预期返回 `code: 0`，且 `data.code` 为 `0001`：

```powershell
Invoke-RestMethod `
  -Uri "http://127.0.0.1:6083/channel/data/getByCode" `
  -Method POST `
  -ContentType "application/json" `
  -Body '{"code":"0001"}'
```

- 网关和前端代理链路会经过 `RequestValidationFilter`，必须带 RSA 签名，不适合直接发送裸 JSON。建议直接运行下面的完整验证脚本，它会自动生成签名并验证直连服务、网关和前端代理：

```powershell
.\scripts\validate-local-startup.ps1 -SkipDockerUp -SkipBuild
```

- 验证脚本会自动读取根目录 `.env`；如果你临时覆盖过端口，确保同一个终端里也带着对应的 `DAMAI_*` 环境变量即可。
