# damai-ai 前端

`damai-ai/vue` 是 `damai-ai` 的智能助手前端，负责会话列表、消息流、运行事件、Markdown 渲染、规则问答、业务助手交互和登录跳转。

## 项目定位

该前端面向 AI 交互层，不直接访问票务数据库。它通过 `damai-core-service` 与后端通信，再由后端完成模型调用、RAG 检索、MCP 查询和 `damai-pro` 业务工具调用。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 框架 | Vue 3.4、Vite 6、TypeScript |
| UI | Naive UI、Heroicons |
| 状态与路由 | Pinia、Vue Router |
| Markdown | marked、highlight.js、DOMPurify |
| 测试 | Vitest、Vue Test Utils、jsdom |
| 运行时 | Node `24.x`，脚本已通过 `npx -p node@24.13.0` 固定执行版本 |

## 环境变量

复制模板：

```bash
cp .env.example .env
```

关键变量：

| 变量 | 说明 |
| --- | --- |
| `VITE_DAMAI_AI_BASE_URL` | AI 核心服务基础地址，默认 `http://127.0.0.1:6089` |
| `VITE_DAMAI_AI_PROXY_TARGET` | Vite 代理目标，默认 `http://127.0.0.1:6089` |
| `VITE_DAMAI_PRO_LOGIN_URL` | 跳转到 `damai-pro` 用户端登录页，当前为 `http://127.0.0.1:15173/login` |

## 启动

```bash
npm install
npm run dev -- --host 127.0.0.1 --port 15174 --strictPort
```

如果通过工作区脚本启动，端口由 `DAMAI_AI_FRONTEND_PORT` 控制，当前推荐值为 `15174`。

## 构建与测试

```bash
npm run type-check
npm run build
npm run test
npm run test:watch
```

## 开发代理

`vite.config.js` 将 `/damai-ai-dev` 转发到 `VITE_DAMAI_AI_PROXY_TARGET`，默认目标为 `damai-core-service:6089`。

## 后端依赖

启动前请确认：

- `damai-core-service:6089` 已启动。
- 如果使用业务助手，`damai-pro` 网关 `6085` 与用户端 `15173` 可访问。
- 如果使用运维助手，`damai-mcp-log-service:8085` 与 `damai-mcp-metrics-service:8086` 可访问。
- 如果使用 RAG，Qdrant 与模型 embedding 配置可用。

## 常见问题

- **Node 版本不一致**：优先使用仓库脚本，或根据 `.nvmrc` 切换到 Node 24。
- **AI 接口失败**：检查 `VITE_DAMAI_AI_PROXY_TARGET` 与 `damai-core-service` 健康状态。
- **登录跳转错误**：检查 `VITE_DAMAI_PRO_LOGIN_URL` 是否指向当前 `damai-pro` 用户端端口。
- **Markdown 显示异常**：检查内容是否被 DOMPurify 过滤，或代码高亮语言是否受支持。

## 相关文档

- [`../README.md`](../README.md)：`damai-ai` 后端架构与启动说明。
- [`../../damai-pro/docs/damai-ai-integration.md`](../../damai-pro/docs/damai-ai-integration.md)：AI 与票务系统联调说明。

