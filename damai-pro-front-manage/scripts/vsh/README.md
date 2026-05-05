# @vben/vsh

`vsh` 是后台管理前端 monorepo 的 Node.js Shell 工具集合，用于依赖检查、循环依赖扫描、lint、格式化和发布前检查等工程治理任务。

## 项目内用途

根目录脚本会通过 `vsh` 执行：

- `pnpm check:dep`
- `pnpm check:circular`
- `pnpm lint`
- `pnpm format`
- `pnpm publint`

## 常用命令

```bash
pnpm check
pnpm lint
pnpm format
```

## 维护约束

- 工具命令应保持工程治理职责，不承载业务逻辑。
- 修改检查规则后需要同步验证 `pnpm check`。
- CI 或本地提交前建议至少执行类型检查和 lint。

