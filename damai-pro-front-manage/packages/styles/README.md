# @vben/styles

`@vben/styles` 用于多个应用共享样式入口，并继承 `@vben-core/design` 的基础能力。

## 职责

- 提供公共样式、主题变量和基础样式入口。
- 统一后台管理端视觉规范。
- 降低应用层重复样式定义。

## 使用

```bash
pnpm add @vben/styles
```

```ts
import '@vben/styles';
```

## 维护约束

- 全局样式变更需要评估所有 app。
- 页面私有样式应留在页面目录。
- 不要在样式包中写业务请求或运行时代码。

