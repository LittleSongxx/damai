# damai-pro 用户端前端

`damai-pro/vue3` 是 `damai-pro` 的普通用户购票前端，负责首页浏览、节目检索、节目详情、票档选择、座位选择、登录注册、订单查看与支付入口等页面。

## 项目定位

该前端只负责用户交互和接口调用，库存扣减、座位锁定、订单创建、支付状态和鉴权校验均由 `damai-pro` 后端微服务完成。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 框架 | Vue 3.2、Vite 3 |
| UI | Element Plus、`@element-plus/icons-vue` |
| 状态与路由 | Pinia、Vue Router |
| 请求与安全 | Axios、CryptoJS、JSEncrypt、jsrsasign |
| 图表与编辑 | ECharts、Vue Quill |

## 环境要求

- Node.js 与 npm
- 已启动 `damai-pro` 后端，尤其是 `damai-gateway-service:6085`
- 已准备 `damai-pro/.env` 中的本地基础设施端口

## 启动

```bash
npm install
npm run dev -- --host 127.0.0.1 --port 15173 --strictPort
```

如果通过工作区脚本启动，端口由 `damai-pro/.env` 中的 `DAMAI_PRO_FRONTEND_PORT` 控制，当前推荐值为 `15173`。

## 构建

```bash
npm run build
npm run preview
```

## 开发代理

开发环境读取 `.env.development`：

| 变量 | 当前含义 |
| --- | --- |
| `VITE_APP_BASE_API` | 前端开发代理前缀，当前为 `/barley-dev` |
| `VITE_APP_URL` | 代理目标，当前为 `http://127.0.0.1:6085` |
| `VITE_SIGN_FLAG` | 是否启用签名调用 |
| `VITE_CODE` | 平台渠道码，当前为 `0001` |
| `VITE_CREATE_ORDER_VERSION` | 下单接口版本，当前为 `4`，对应异步下单链路 |

`vite.config.js` 会将 `/barley-dev` 前缀去掉后转发到网关，因此业务接口最终进入 `damai-gateway-service` 的 `/damai/**` 路由。

## 后端依赖

启动前至少确认以下服务健康：

- `damai-gateway-service:6085`
- `damai-user-service:6082`
- `damai-program-service:6086`
- `damai-order-service:8081`
- `damai-pay-service:6087`

## 常见问题

- **接口 404**：检查 `VITE_APP_BASE_API`、Vite 代理 rewrite 和网关路由是否匹配。
- **下单失败**：检查 `VITE_CREATE_ORDER_VERSION`、节目服务、订单服务和 RabbitMQ 消费状态。
- **鉴权失败**：检查登录态、签名开关、渠道码和网关过滤器。
- **端口占用**：使用 `--port 15173 --strictPort`，或修改 `DAMAI_PRO_FRONTEND_PORT`。

## 相关文档

- [`../README.md`](../README.md)：`damai-pro` 后端说明。
- [`../docs/local-dev.md`](../docs/local-dev.md)：本地开发与验证说明。

