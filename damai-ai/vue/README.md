# damai-ai 管理工作台前端

面向客服运营、知识运营、数据问数和运维治理的管理工作台。普通客户侧 AI 浮窗在 `damai-pro/vue3` 中运行；本前端聚焦坐席队列、知识缺口、RAG 质量、语义目录、Ops Evidence 和质量门禁。

```mermaid
graph LR
    User[客服/运营/管理员] --> Vue[Vue 3 前端 :15174]
    Vue -->|/assistant/**| Core[damai-core-service :6089<br/>客服 / 知识 / DataOps / Ops Evidence]
    Core --> Models[大模型]
    Core --> RAG[RAG / 语义目录 / 证据 Provider]
    Core --> Pro[damai-pro 网关与 OpsEvent]
```

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 框架 | Vue 3.4 · Vite 6 · TypeScript |
| UI | Naive UI · Heroicons |
| 状态与路由 | Pinia · Vue Router |
| Markdown | marked · highlight.js · DOMPurify |
| 测试 | Vitest · Vue Test Utils · jsdom |
| 运行时 | Node 24.x（见 `.nvmrc`） |

## 环境变量

```bash
cp .env.example .env
```

| 变量 | 说明 |
| --- | --- |
| `VITE_DAMAI_AI_BASE_URL` | AI 核心服务地址，默认 `http://127.0.0.1:6089` |
| `VITE_DAMAI_AI_PROXY_TARGET` | Vite 代理目标 |
| `VITE_DAMAI_PRO_LOGIN_URL` | 跳转 `damai-pro` 登录页 |

## 启动

```bash
npm install
npm run dev -- --host 127.0.0.1 --port 15174 --strictPort
```

通过工作区脚本启动时端口由 `DAMAI_AI_FRONTEND_PORT` 控制。

## 构建与测试

```bash
npm run type-check
npm run build
npm run test
npm run test:watch
```

## 开发代理

`vite.config.js` 将 `/damai-ai-dev` 前缀转发到 `VITE_DAMAI_AI_PROXY_TARGET`（默认 `damai-core-service:6089`）。

## 后端依赖

| 服务 | 用途 |
| --- | --- |
| `damai-core-service:6089` | 必须 — AI API 聚合服务 |
| `damai-pro` 网关 `:6085` | 票务查询、内部 reservation 和业务事件来源 |
| Qdrant + Embedding 模型 | RAG 知识问答所需 |
| ES + Prometheus + Alertmanager + SkyWalking | Ops Evidence 运维证据来源 |

## 常见问题

| 问题 | 排查 |
| --- | --- |
| Node 版本不一致 | 按 `.nvmrc` 切换到 Node 24 |
| AI 接口失败 | 检查代理目标和 `damai-core-service` 健康状态 |
| 登录跳转错误 | 检查 `VITE_DAMAI_PRO_LOGIN_URL` 端口 |
| Markdown 异常 | 检查 DOMPurify 过滤或高亮语言支持 |

## 相关文档

- [`../README.md`](../README.md) — damai-ai 后端架构
- [`../../damai-pro/docs/damai-ai-integration.md`](../../damai-pro/docs/damai-ai-integration.md) — 联调指南
