# Service Orchestration and ViewModel Validation

## 背景

- 创建日期：2026-07-20
- 功能标识：`service-and-viewmodel-validation`
- 需求来源：实现 `ARCHITECTURE.md` 的 Phase 3（共享通知 Service 层）与 Phase 5（ViewModel 行为验证）。

## 目标

- 在 `commonMain/service` 实现 `NotificationOrchestrator`，使 Task/Habit 的持久化生命周期能由共享逻辑决定通知的创建、更新、取消与全量重建。
- 为通知能力引入可替换的 common contract，以便 service 与 ViewModel 测试不依赖 iOS/Android/HarmonyOS 的系统 API。
- 将任务相关 ViewModel 的写入路径接入 orchestrator，并为关键 Task/Habit 行为加入可执行测试。
- 提供 Android、iOS Simulator、macOS、HarmonyOS 的编译/链接验证；macOS 执行 native SQLite 与 ViewModel 集成测试。

## 非目标

- 不实现 Android BroadcastReceiver、iOS notification category、HarmonyOS reminderAgent 的宿主桥接或权限申请。
- 不把当前 Android/iOS/HarmonyOS 平台 actual 的简化调度算法升级为生产级时区/精确闹钟实现。
- 不创建或修改 Android Compose、SwiftUI、ArkUI 页面；本仓库没有这些宿主 UI 工程。
- 不将 Phase 5 的“三端 UI 测试”误标为完成：它们必须在各宿主工程接入 KMP framework 后执行。

## 用户场景

1. 用户创建、编辑、完成、归档或删除有截止时间的 Task 时，跨端一致地新增、重建或取消其提醒。
2. 用户创建、编辑、归档或删除带启用提醒的 Habit 时，跨端一致地新增、重建或取消其提醒。
3. 开发者可在 macOS native SQLite 测试中，以 fake notification gateway 验证 service 与 ViewModel 的状态/通知结果，而不调用系统通知 API。

## 验收标准

- `NotificationOrchestrator` 只为未删除且 `ACTIVE`、带 `dueAt` 的 Task 调度通知；其他状态或无截止时间均取消已有通知。
- 对未删除且 `ACTIVE`、带已启用 `HabitReminder` 的 Habit 调度通知；其他情况取消已有通知。
- `TaskListViewModel`、`TaskDetailViewModel`、`TaskEditorViewModel`、`ArchiveViewModel` 的 Task 写路径在注入 orchestrator 后调用正确的生命周期入口，未注入时保持现有行为。
- service 的策略测试在 `commonTest` 通过；ViewModel + SQLite 集成测试在 `macosArm64Test` 通过。
- `compileDebugKotlinAndroid`、`compileKotlinIosSimulatorArm64`、`linkDebugFrameworkIosSimulatorArm64`、`macosArm64Test`、`linkDebugSharedOhosArm64` 通过。

## 风险与约束

- `NotificationService` 是现有 public `expect class`；新增的 gateway 必须保持其构造方式和平台实际实现兼容。
- 现有 ViewModel 默认使用 `Dispatchers.Main`，测试必须注入 test scope，不能依赖 macOS 的 Main dispatcher。
- macOS `NotificationService` 是 fail-fast 开发实现；测试只能通过 common fake gateway 进入 orchestrator。
- 当前 CPF Kotlin/Native + Xcode 26.5 仍无法链接 `iosSimulatorArm64Test` 的测试可执行文件（`_Kotlin_KonanStartStub`）。iOS framework 链接是本轮 iOS 验收，运行时测试继续记录为工具链阻塞。

## 背景

- 创建日期：2026-07-20
- 功能标识：`service-and-viewmodel-validation`
- 需求来源：

## 目标

- 

## 非目标

- 

## 用户场景

1. 

## 验收标准

- 

## 风险与约束

- 
