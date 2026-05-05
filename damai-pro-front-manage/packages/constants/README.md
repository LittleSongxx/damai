# @vben/constants

`@vben/constants` 用于多个应用共享常量，并继承 `@vben-core/shared/constants` 的基础能力。

## 职责

- 存放跨应用共享的路由路径、缓存 key、枚举值等常量。
- 避免同一常量在多个 app 或 package 中重复定义。
- 为管理端统一行为提供稳定入口。

## 使用

```bash
pnpm add @vben/constants
```

```ts
import { LOGIN_PATH } from '@vben/constants';
```

## 维护约束

- 只放通用常量。
- 业务接口返回的字典值应优先来自后端或业务模块。
- 修改常量前需要评估所有 workspace 消费方。

