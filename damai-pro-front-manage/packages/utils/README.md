# @vben/utils

`@vben/utils` 用于多个应用共享工具函数，并继承 `@vben-core/shared/utils` 的基础能力。

## 职责

- 提供字符串、对象、数组、日期、浏览器环境等通用工具。
- 避免多个包重复实现基础函数。
- 保持纯函数优先，降低副作用。

## 使用

```bash
pnpm add @vben/utils
```

```ts
import { isString } from '@vben/utils';
```

## 维护约束

- 不写业务接口调用。
- 不依赖具体页面状态。
- 带副作用逻辑优先放入 `effects` 或应用层。

