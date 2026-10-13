# 鸿蒙专注修复 Module Plan

## 模块拆分

1. Sheet宿主保留Repository命令、ticker、稳定sessionUID与metadata校验；视图只做直接组件声明。
2. 动态Component：活动计时、结果/摘要、历史、打卡确认、目标选择；复用圆形操作与原生环形进度。
3. 原生规范：SegmentButtonV2、关闭按钮、顶部布局、胶囊按钮、ImmersiveMaterialScope和语义图标色。
4. miniBar：独立样式策略（持久保存折叠/展开），HdsTabs官方USER_CLICK回调和单行内容，旧API浮动栏兼容。
5. 验证：宿主合同保留；新增状态投影/miniBar策略执行测试与组件/材质源码断言。

## 平台映射

只改鸿蒙 `entry/src/main/ets/pages/routes/focus`，以及 `pages/components/{AdaptedHdsTabs,TaskItemCard}`、`pages/routes/main/{TaskListSection,HabitListSection}` 的专注绑定/右滑块。复用既有ImmersiveMaterialScope、ThemeManager、SheetCompat；iOS/Android只读或不访问。

## 文件拆分策略

动态区域拆组件，不向现有Sheet/TaskList大文件继续堆布局。Builder仅用于平台要求的Sheet/miniBar/材质插槽，不接收动态timer快照。

## 风险点

V1/V2组件互操作使用selectedIndex回调，不混用装饰器；miniBar样式回调不可因APP_TRIGGER/NORMAL意外打开Sheet；当前session变化时联合操作读取新快照；系统材质与颜色沿既有兼容层，不发明新API。
