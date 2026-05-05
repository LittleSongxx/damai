# damai-pro

## 版权与访问说明

为了保障项目作者与学习用户权益，`damai-pro` 当前不按公开开源项目方式分发。请勿在未获得原作者明确授权的情况下，将代码、配套资料或私有仓库内容上传到 GitHub、Gitee 等开放平台。

- **知识星球规则**：[《侵权责任法》、《著作权法》和《信息网络传播权保护条例》](https://support.zsxq.com/guidance.html)
- **项目版权说明**：[《中华人民共和国著作权法实施条例》](https://gitcode.com/java_up/introduce/blob/main/copyright_%E4%B8%AD%E5%8D%8E%E4%BA%BA%E6%B0%91%E5%85%B1%E5%92%8C%E5%9B%BD%E8%91%97%E4%BD%9C%E6%9D%83%E6%B3%95%E5%AE%9E%E6%96%BD%E6%9D%A1%E4%BE%8B.pdf)

## 项目定位

`damai-pro` 是大麦票务系统的高并发微服务版本，核心目标是把“演出浏览、选座锁座、库存扣减、异步下单、支付、后台监控、数据迁移与故障恢复”串成一套可本地运行、可压测、可排障的工程样板。

项目重点演示以下问题的工程化解法：

- **高并发下单**：通过多版本下单链路、库存缓存、座位锁定、异步订单、限流与幂等控制提高吞吐。
- **数据一致性**：围绕 Redis、MySQL、RabbitMQ、Seata、补偿任务和后台核对能力处理极端失败场景。
- **分库分表**：基于 ShardingSphere、基因法和虚拟分片思想组织订单、支付、节目、用户等数据。
- **服务治理**：使用 Nacos、Gateway、OpenFeign、Sentinel、Spring Boot Admin 管理服务发现、路由、调用与监控。
- **可观测性**：通过 API 数据采集、Elasticsearch、Prometheus、后台管理与 AI MCP 服务提供诊断数据。

## 模块结构

| 模块 | 说明 |
| --- | --- |
| `damai-common` | 公共模型、异常、响应结构、工具类、自动配置基础 |
| `damai-redis-tool-framework` | Redis 缓存、Lua、分布式缓存访问封装 |
| `damai-elasticsearch-framework` | Elasticsearch / Easy-ES 相关封装 |
| `damai-id-generator-framework` | 分布式 ID 生成器，支持基于 Redis 生成 `workId` |
| `damai-spring-cloud-framework` | Spring Cloud、OpenFeign、网关与通用微服务能力 |
| `damai-thread-pool-framework` | 线程池封装与异步执行支撑 |
| `damai-redisson-framework` | Redisson 分布式锁、业务锁注解能力 |
| `damai-captcha-manage-framework` | 验证码相关能力 |
| `damai-server-client` | 各业务服务对外 Feign Client 与 DTO |
| `damai-server` | 实际可启动的业务微服务集合 |
| `vue3` | 用户端前端，基于 Vue 3、Vite、Element Plus |
| `docs` | 本地开发、AI 一体化联调等补充文档 |
| `scripts` | Windows/PowerShell 本地开发辅助脚本 |

## 微服务与端口

| 服务 | 端口 | 职责 |
| --- | --- | --- |
| `damai-admin-service` | `10082` | Spring Boot Admin 与后端管理支撑 |
| `damai-base-data-service` | `6083` | 渠道、地区、字典等基础数据 |
| `damai-customize-service` | `6084` | API 采集、定制化业务与后台查询能力 |
| `damai-user-service` | `6082` | 用户、登录态、观演人等用户域能力 |
| `damai-program-service` | `6086` | 节目、场次、票档、座位、下单前置链路 |
| `damai-pay-service` | `6087` | 支付单、支付回调、支付状态流转 |
| `damai-order-service` | `8081` | 订单创建、取消、支付状态、MQ 消费 |
| `damai-migrate-service` | `6088` | 分片迁移、扩容与数据迁移支撑 |
| `damai-gateway-service` | `6085` | 统一入口、路由、签名校验、跨域与鉴权过滤 |

## 基础设施

`docker-compose.yml` 统一编排本地依赖：

| 组件 | 默认地址 |
| --- | --- |
| MySQL | `127.0.0.1:13306` |
| Redis | `127.0.0.1:16379` |
| Nacos | `127.0.0.1:18848/nacos` |
| RabbitMQ | AMQP `5672`，管理端 `15672` |
| Elasticsearch | `127.0.0.1:19200` |
| Seata | `127.0.0.1:8091` |
| Sentinel Dashboard | `127.0.0.1:8082` |
| Prometheus | `127.0.0.1:9090` |
| Qdrant | `127.0.0.1:16333` |
| Ollama | `127.0.0.1:11434` |

本地环境变量模板见 `.env.example`。如果端口冲突，优先修改 `damai-pro/.env` 后再启动。

## 快速启动

### 推荐：工作区一键启动

在工作区根目录执行：

```bash
bash scripts/damai-stack.sh start
```

常用命令：

```bash
bash scripts/damai-stack.sh status
bash scripts/damai-stack.sh stop
bash scripts/damai-stack.sh start --skip-build
bash scripts/damai-stack.sh start --skip-db-init
bash scripts/damai-stack.sh start --skip-frontend
```

脚本会自动读取 `damai-pro/.env`，完成 Docker 依赖启动、数据库初始化、分片配置生成、Maven 构建、后端服务启动和用户端前端启动。

### 手动启动顺序

如果需要手动调试，建议按以下顺序启动：

1. `docker compose --env-file .env --profile ai up -d`
2. 初始化 SQL，或使用 `scripts/init-databases.ps1`
3. `damai-admin-service`
4. `damai-base-data-service`
5. `damai-customize-service`
6. `damai-user-service`
7. `damai-program-service`
8. `damai-pay-service`
9. `damai-order-service`
10. `damai-migrate-service`
11. `damai-gateway-service`
12. `vue3` 用户端前端

更细的 Windows/IDEA 本地开发说明见 [`docs/local-dev.md`](docs/local-dev.md)。

## 用户端前端

用户端前端位于 `vue3`：

```bash
cd damai-pro/vue3
npm install
npm run dev -- --host 127.0.0.1 --port 15173 --strictPort
```

前端通过 Vite 代理访问网关，网关地址为 `http://127.0.0.1:6085`。如果使用一键脚本，端口由 `DAMAI_PRO_FRONTEND_PORT` 控制，当前模板推荐 `15173`。

## 核心业务流

### 节目浏览

用户端请求经 `damai-gateway-service` 进入 `damai-program-service`，读取节目、场次、票档、余票和座位信息。热点数据优先走 Redis，本地服务通过 Nacos 发现和 Feign 协作。

### 下单链路

1. 用户选择节目、票档和座位。
2. `damai-program-service` 校验节目状态、票档库存、座位状态和用户身份。
3. 服务按下单版本执行库存扣减、座位锁定和风控校验。
4. 下单消息写入 RabbitMQ。
5. `damai-order-service` 消费消息，创建订单并维护订单状态。
6. 支付链路由 `damai-pay-service` 处理，最终回写订单状态。

### 数据核对与恢复

系统围绕 Redis、数据库、RabbitMQ 设计了数据核对和补偿入口，用于处理缓存宕机、MQ 延迟/丢失、数据库与缓存不一致、库存回滚等场景。

## 设计亮点

- **多版本下单演进**：`ProgramOrderController` 暴露多个创建订单版本，便于对比不同高并发策略的效果。
- **异步订单创建**：节目服务负责前置校验和扣减，订单服务通过 RabbitMQ 消费创建订单，削峰填谷。
- **分布式锁与防重**：基于 Redisson、业务锁注解和重复执行限制避免重复提交和并发冲突。
- **分库分表路由**：通过 ShardingSphere 和基因法保证按用户或订单维度定位数据分片。
- **虚拟分片扩容**：在物理分片与业务数据之间增加虚拟分片映射，降低扩容迁移风险。
- **中间件故障兜底**：针对 Redis、RabbitMQ、数据库不一致等场景提供恢复和后台核对思路。
- **链路可观测**：API 数据、日志、指标和后台页面可为 `damai-ai` 运维助手提供诊断证据。

## 与 damai-ai 联动

`damai-ai` 会调用 `damai-pro` 网关接口完成节目检索、节目详情、票档查询、当前用户、观演人和下单准备等能力。联调说明见 [`docs/damai-ai-integration.md`](docs/damai-ai-integration.md)。

关键依赖：

- `damai-gateway-service` 必须可访问。
- `damai-user-service`、`damai-program-service`、`damai-order-service` 等业务服务需要在 Nacos 中注册成功。
- `damai-ai/.env` 中的 `DAMAI_AI_*_URL` 需要指向当前网关地址。

## 常见问题

- **Nacos 注册失败**：检查 `DAMAI_NACOS_ADDR`、用户名密码和 Docker 容器状态。
- **数据库连接失败**：检查 `DAMAI_MYSQL_HOST`、`DAMAI_MYSQL_PORT`、账号密码和分片 YAML。
- **前端接口 404**：确认 Vite 代理前缀、网关路由和 `/damai/**` 路径是否一致。
- **网关裸请求失败**：网关存在签名/请求校验过滤器，建议通过前端或验证脚本发起请求。
- **订单未创建**：检查 RabbitMQ 队列、订单服务消费者、Redis 座位锁和订单丢弃队列。
- **AI 无法下单**：先确认 `damai-pro` 用户端已登录，再确认 AI 环境变量中的网关接口地址。

## 相关文档

- [`docs/local-dev.md`](docs/local-dev.md)：本地开发、IDEA 运行配置、数据库初始化与验证。
- [`docs/damai-ai-integration.md`](docs/damai-ai-integration.md)：`damai-ai` 与 `damai-pro` 一体化联调。
- [`vue3/README.md`](vue3/README.md)：用户端前端说明。
- [`damai-id-generator-framework/README.md`](damai-id-generator-framework/README.md)：分布式 ID 生成器说明。

