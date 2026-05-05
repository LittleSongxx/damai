# @vben/icons

`@vben/icons` 用于多个应用共享图标能力，并继承 `@vben-core/icons` 的基础封装。

## 职责

- 管理跨应用通用图标。
- 封装图标组件、图标注册和图标使用入口。
- 统一后台管理端视觉资源。

## 使用

```bash
pnpm add @vben/icons
```

```ts
import { X } from '@vben/icons';
```

## 维护约束

- 业务专属图标应确认是否需要共享。
- 删除图标前需要检索所有使用点。
- 保持导出命名清晰，避免和第三方图标重名。

