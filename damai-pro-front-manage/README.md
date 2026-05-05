# damai-pro-front-manage

## 版权与访问说明

为了保障项目作者与学习用户权益，`damai-pro-front-manage` 当前不按公开开源项目方式分发。请勿在未获得原作者明确授权的情况下，将代码、配套资料或私有仓库内容上传到 GitHub、Gitee 等开放平台。

- **知识星球规则**：[《侵权责任法》、《著作权法》和《信息网络传播权保护条例》](https://support.zsxq.com/guidance.html)
- **项目版权说明**：[《中华人民共和国著作权法实施条例》](https://gitcode.com/java_up/introduce/blob/main/copyright_%E4%B8%AD%E5%8D%8E%E4%BA%BA%E6%B0%91%E5%85%B1%E5%92%8C%E5%9B%BD%E8%91%97%E4%BD%9C%E6%9D%83%E6%B3%95%E5%AE%9E%E6%96%BD%E6%9D%A1%E4%BE%8B.pdf)

## 项目定位

`damai-pro-front-manage` 是 `damai-pro` 的后台管理前端，基于 `vue-vben-admin` monorepo 改造，用于承载运营管理、数据查询、监控排障和后台处理类页面。

它与 `damai-pro/vue3` 的区别：

- `damai-pro/vue3` 面向普通用户购票。
- `damai-pro-front-manage` 面向管理端和运营端。
- 管理端请求通过 Vite 代理转发到 `damai-gateway-service`，再访问后端微服务。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 工程形态 | pnpm workspace、Turbo、Vite |
| 前端框架 | Vue 3、TypeScript、Pinia、Vue Router |
| UI | Element Plus、Vben UI 体系 |
| 代码质量 | ESLint、Stylelint、Prettier、cspell、Vitest |
| 目标应用 | `apps/web-ele`，包名 `@vben/web-ele` |

## 环境要求

| 工具 | 要求 |
| --- | --- |
| Node.js | `>= 20.10.0`，建议 `20.15.0+` |
| pnpm | `>= 9.12.0`，仓库声明 `pnpm@10.10.0` |
| 后端 | 需要先启动 `damai-pro` 后端，尤其是网关 `6085` |

建议使用 Corepack 管理 pnpm：

```bash
corepack enable
corepack prepare pnpm@10.10.0 --activate
```

## 启动方式

安装依赖：

```bash
pnpm install
```

启动 Element Plus 管理端应用：

```bash
pnpm dev:ele
```

也可以使用交互式启动：

```bash
pnpm dev
```

默认访问地址来自 `apps/web-ele/.env.development`：

```text
http://localhost:5878
```

## 构建与检查

```bash
pnpm build:ele
pnpm check
pnpm lint
pnpm test:unit
```

常用脚本：

| 命令 | 说明 |
| --- | --- |
| `pnpm dev:ele` | 启动 `apps/web-ele` |
| `pnpm build:ele` | 构建 `apps/web-ele` |
| `pnpm check:type` | 执行类型检查 |
| `pnpm check:dep` | 检查依赖关系 |
| `pnpm check:circular` | 检查循环依赖 |
| `pnpm format` | 格式化代码 |

## 后端代理

`apps/web-ele/vite.config.mts` 中配置了开发代理：

| 前端前缀 | 后端目标 |
| --- | --- |
| `/api` | `http://localhost:6085/damai` |

因此管理端启动前需要确认：

- `damai-gateway-service` 已启动并监听 `6085`。
- 网关路由能访问后台所需的业务服务。
- 登录态、签名、权限等后端校验逻辑与当前页面请求一致。

## 目录说明

| 路径 | 说明 |
| --- | --- |
| `apps/web-ele` | Element Plus 版本后台管理应用 |
| `packages/@core` | Vben 核心基础能力，不建议放业务逻辑 |
| `packages/effects` | 与状态、路由、偏好设置、组件库有轻耦合的共享逻辑 |
| `packages/constants` | 多应用共享常量 |
| `packages/icons` | 图标封装 |
| `packages/styles` | 公共样式入口 |
| `packages/types` | 共享类型定义 |
| `packages/utils` | 通用工具函数 |
| `internal` | 内部构建、Vite、lint、tailwind 等工程配置 |
| `scripts` | monorepo 辅助命令，例如 `turbo-run`、`vsh` |

## 与 damai-pro 的关系

管理端只负责页面展示和操作入口，真实业务逻辑仍由 `damai-pro` 后端微服务处理。常见依赖包括：

- 基础数据服务
- 用户服务
- 节目服务
- 订单服务
- 支付服务
- 定制化与 API 数据采集服务
- 网关签名、鉴权和路由能力

## 常见问题

- **页面打不开**：检查 Node.js 版本、pnpm 版本和 `apps/web-ele/.env.development` 中的 `VITE_PORT`。
- **接口 404**：确认代理目标 `http://localhost:6085/damai` 与网关实际地址一致。
- **接口鉴权失败**：确认后端网关、登录态、签名与管理端请求逻辑一致。
- **依赖安装失败**：先执行 `corepack enable`，再使用仓库声明的 pnpm 版本安装。
- **构建内存不足**：根脚本已使用 `NODE_OPTIONS=--max-old-space-size=8192`，可按机器内存继续调整。

## 相关文档

- [`../damai-pro/README.md`](../damai-pro/README.md)：后端微服务说明。
- [`apps/web-ele/src/locales/README.md`](apps/web-ele/src/locales/README.md)：应用国际化扩展说明。
- [`packages/@core/README.md`](packages/@core/README.md)：核心包维护边界。
- [`scripts/turbo-run/README.md`](scripts/turbo-run/README.md)：monorepo 命令选择工具。

