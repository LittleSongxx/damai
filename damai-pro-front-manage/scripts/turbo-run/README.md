# @vben/turbo-run

`turbo-run` 是 monorepo 命令选择工具，用于在多个 workspace 包中查找并运行指定脚本。

## 项目内用途

`damai-pro-front-manage` 根目录的 `pnpm dev`、`pnpm preview` 等脚本会通过 `turbo-run` 提供交互式选择，便于只启动目标应用或包。

## 基本用法

```bash
turbo-run dev
```

执行后工具会：

1. 扫描 workspace 中定义了 `dev` 脚本的包。
2. 提供交互式选择界面。
3. 使用 pnpm filter 在选中的包内运行脚本。

## 常用替代命令

如果目标明确，可以直接运行：

```bash
pnpm dev:ele
pnpm build:ele
```

## 维护约束

- 该工具需要在 monorepo 根目录执行。
- 新增应用包时应在包的 `package.json` 中声明对应脚本。
- 批处理或 CI 场景优先使用明确的 `pnpm --filter` 命令。

