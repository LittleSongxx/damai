# @vben/hooks

`@vben/hooks` 用于多个 app 共享组合式函数，并继承 Vben hooks 体系能力。

## 职责

- 提供跨应用可复用的 Vue hooks。
- 封装和状态、布局、交互有关的轻量逻辑。
- 避免页面级重复实现。

## 使用

```bash
pnpm add @vben/hooks
```

```ts
import { useNamespace } from '@vben/hooks';
```

## 维护约束

- hook 应保持参数清晰、无隐式业务依赖。
- 不直接写死 `damai-pro` 接口地址。
- 页面专用 hook 应留在页面目录。

