# Native Focus UI Module Plan

## 模块拆分

- 入口：两端Task/Habit右滑动作及预绑定Sheet；删除顶栏和详情入口。
- 设置与计时组件：分段模式、时间预览、圆盘、圆形操作；平台原生材质适配。
- 宿主：保持现有计时状态/数量确认/历史命令，区分设置和本轮计时结果。
- 验证：更新原生UI用例从右滑进入，构建/规模/静态合同及可用模拟器检查。

## 平台映射

iOS `Features/Focus`、`Features/Home/{HomeView,Components/DDLItemCard,Sheets/TaskDetailSheet}`；鸿蒙 `pages/routes/focus`、`pages/routes/main/{TaskListSection,HabitListSection,modify/task/TaskDetailSheet}`、`pages/components/TaskItemCard`。Android只读 `ui/focus`。

## 文件拆分策略

独立RoundAction/Dial/Setup组件，不把图形和模式选择继续堆进宿主；保持原有架构及1000有效行限制。

## 风险点

初始化不能把旧完成记录误当新设置；活动优先；暂停仍显示全局栏；右滑仅揭示按钮，不自动启动；原生材质availability保护；批次后统一编译。
