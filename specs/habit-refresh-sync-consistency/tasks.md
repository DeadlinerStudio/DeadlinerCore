# Habit Refresh and Sync Consistency Tasks

## 阶段 1：规格冻结

- [x] 对照真实 WebDAV 变更日志确认 HarmonyOS Habit Record 已进入远端文档。
- [x] 定义完成后下一次主操作归零，且只清除本次操作日记录。
- [x] 定义 Android 下拉刷新顺序与云同步不可用时的本地回退。

## 阶段 2：基础设施

- [x] 补充 Habit 主操作循环的领域/应用层测试。
- [x] 补充 Habit Record 聚合版本与跨设备合并测试。
- [x] 验证真实远端 JSON 可由当前 Snapshot V4 合同解码。

## 阶段 3：功能实现

- [x] 将完成状态下的主操作统一路由到目标窗口 Clear。
- [x] 允许已完成但仍 due 的 Habit 接收主操作以触发归零。
- [x] Android 下拉刷新接入 `syncOnce()` 并在结束后 reload Task/Habit KMP ViewModel。
- [x] 为 Android 刷新提供独立、可见且可正确结束的状态。

## 阶段 4：验证与回写

- [x] 运行 KMP 目标单测与 Android target 编译。
- [x] 运行 Android `:app:compileDebugKotlin`。
- [x] 执行 feature spec 校验与大文件扫描。
- [x] 将最终实现与验证证据回写本任务清单。

验证证据（2026-08-30）：

- `HabitEnginesTest`、`HabitListViewModelIntegrationTest`、Habit 跨设备聚合版本测试通过。
- 与附件相同的“外层/快照未声明版本、Record 省略默认字段”载荷回归测试通过，HarmonyOS Record 在 Android 数据库侧保留为有效 count=1 打卡。
- `:shared:compileDebugKotlinAndroid`、`:shared:linkDebugFrameworkIosSimulatorArm64`、`:shared:compileKotlinOhosArm64` 均成功。
- Android 自动构建并部署最新版 Core AAR，`:app:compileDebugKotlin` 成功。
- iOS 完整工程构建在 Swift 编译前被既有 Asset Catalog `actool` nil-object 异常阻断；新 shared framework 已成功链接，并确认导出 Swift 方法 `applyPrimaryRecordAction(habitUid:occurredOn:occurredAt:)`。
- 大文件扫描仅发现既有白名单 `DeadlinerPersistenceCApi.kt`（1443 行）；本批未修改其领域规则。
