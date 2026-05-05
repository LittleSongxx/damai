# @vben/plugins

`@vben/plugins` 管理第三方库集成与插件封装，避免应用直接散落初始化逻辑。

## 职责

- 封装 ECharts、Markdown、上传、编辑器等第三方库接入。
- 统一插件配置、按需导出和副作用边界。
- 降低应用层对具体库初始化细节的感知。

## 导出约定

第三方插件应以 subpath 形式引入：

```ts
import { useEcharts } from '@vben/plugins/echarts';
```

## 维护约束

- 不要在包入口一次性引入所有插件。
- 避免无关插件增加应用打包体积。
- 插件封装不应绑定具体业务接口。

