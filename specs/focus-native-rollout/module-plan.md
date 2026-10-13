# Native Focus Rollout Module Plan

## 模块拆分

- 主 agent：规格/行为矩阵、OHOS Core C ABI 缺口、共享只读统计桥接、定向测试与集成审查；commonMain 如需变更先协调。
- iOS agent：SwiftUI / 生命周期 / 通知 / 入口 / 统计，以及 `shared/src/iosMain/kotlin/com/deadliner/bridge/`，不改 OHOS 或 Android。
- HarmonyOS agent：ArkTS / HDS / NAPI / 入口 / 通知 / 统计，Core OHOS 缺口向主 agent 请求。
- Core 领域与持久化复用既有 FocusTimerService、FocusTimerViewModel、FocusStatisticsRepository；平台不另立计时规则。

## 平台映射

- Core：`shared/src/commonMain/kotlin/com/deadliner/{domain,service,viewmodel}/focus/`（优先只读）；OHOS 桥接在 `shared/src/ohosArm64Main/kotlin/com/deadliner/bridge/`。
- iOS：`/Users/aritxonly/Codes/iOS/Deadliner/Deadliner/Features/Focus/`、Main/Home/Habit/Overview 接线、Core/Application Services；保持 DDD 边界。
- HarmonyOS：`/Users/aritxonly/Codes/HarmonyOS/Deadliner/entry/src/main/ets/{model,common/kmp,pages/routes/focus,utils/notification}`、Index 与列表入口；NAPI 在 `entry/src/main/cpp/`。
- Android：`/Users/aritxonly/Codes/Android/Deadliner` 仅只读基准，禁止本轮写入、编译部署。

## 文件拆分策略

设置/数量确认/历史/摘要与原生底栏分拆，避免继续扩充大型宿主。平台 agent 各自掌握仓库内部文件，跨 Core 目录必须通知；集成前检查 1000 行规模和 diff。无需为此创建新应用聊天。

## 风险点

SDK 与运行 API版本可能不同；旧草稿 Task-only decode 不能丢新 Habit 字段；桥接 state、命令、目标选择和通知必须用 typed target，不用空 taskUid 冒充 Habit。后台系统能力需按平台实现且验证受限时不做等价保证。用户另一对话修改 Android，不用工作区差异推断是本轮变更。

