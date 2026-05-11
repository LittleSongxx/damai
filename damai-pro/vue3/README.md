# damai-pro 用户端前端

用户购票前端，覆盖首页浏览、节目检索、详情查看、票档选择、座位选择、登录注册、订单管理与支付入口。

```mermaid
graph LR
    User[用户] --> Vue[Vue 3 前端 :15173]
    Vue -->|/barley-dev → rewrite| GW[damai-gateway :6085]
    GW --> Services[业务微服务集群]
```

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 框架 | Vue 3.2 · Vite 3 |
| UI | Element Plus · @element-plus/icons-vue |
| 状态与路由 | Pinia · Vue Router |
| 请求与安全 | Axios · CryptoJS · JSEncrypt · jsrsasign |
| 图表与编辑 | ECharts · Vue Quill |

## 启动

```bash
npm install
npm run dev -- --host 127.0.0.1 --port 15173 --strictPort
```

通过工作区脚本启动时端口由 `DAMAI_PRO_FRONTEND_PORT` 控制。

## 构建

```bash
npm run build
npm run preview
```

## 开发代理

| 变量 | 说明 |
| --- | --- |
| `VITE_APP_BASE_API` | 代理前缀，当前 `/barley-dev` |
| `VITE_APP_URL` | 代理目标 `http://127.0.0.1:6085` |
| `VITE_SIGN_FLAG` | 签名开关 |
| `VITE_CODE` | 渠道码 `0001` |
| `VITE_CREATE_ORDER_VERSION` | 下单版本 `4`（异步链路） |

`vite.config.js` 将 `/barley-dev` 前缀去掉后转发到网关 `/damai/**` 路由。

## 后端依赖

| 服务 | 端口 |
| --- | --- |
| damai-gateway-service | `6085` |
| damai-user-service | `6082` |
| damai-program-service | `6086` |
| damai-order-service | `8081` |
| damai-pay-service | `6087` |

## 常见问题

| 问题 | 排查 |
| --- | --- |
| 接口 404 | 检查代理前缀、网关路由、`/damai/**` 路径 |
| 下单失败 | 检查下单版本、节目服务、RabbitMQ 消费 |
| 鉴权失败 | 登录态、签名开关、渠道码、网关过滤器 |
| 端口占用 | `--strictPort` 或修改 `DAMAI_PRO_FRONTEND_PORT` |

## 相关文档

- [`../README.md`](../README.md) — damai-pro 后端说明
- [`../docs/local-dev.md`](../docs/local-dev.md) — 本地开发与验证

