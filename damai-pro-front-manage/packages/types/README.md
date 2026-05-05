# @vben/types

`@vben/types` 用于多个应用共享 TypeScript 类型，并继承 `@vben-core/typings` 的基础能力。

## 职责

- 存放跨应用共享类型。
- 统一 SelectOption、表格配置、菜单、路由等通用结构。
- 避免类型在多个包中重复定义。

## 使用

```bash
pnpm add @vben/types
```

```ts
import type { SelectOption } from '@vben/types';
```

## 维护约束

- 类型导入优先使用 `import type`。
- 业务接口 DTO 应跟随业务模块，不要全部塞进通用 types。
- 修改共享类型前需要确认所有 workspace 消费方。

