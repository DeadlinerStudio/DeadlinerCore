# Service Orchestration and ViewModel Validation Module Plan

## 模块拆分

- `platform-notification-contract`：`NotificationGateway` 接口与现有 `NotificationService` actual 的兼容实现。
- `service-notification`：`NotificationOrchestrator`，集中 Task/Habit 生命周期和全量刷新规则。
- `viewmodel-integration`：将 Task 写入的 ViewModel 接入可选 orchestrator，保持未注入时的兼容性。
- `verification`：common service 策略测试与 macOS SQLite/ViewModel 集成测试。

## 平台映射

- `commonMain/platform`：只暴露可 fake 的通知 gateway；`commonMain/service` 不引用 Apple、Android 或 ArkTS API。
- Android/iOS/HarmonyOS：既有 `NotificationService` actual 继续负责系统调用，作为 gateway 的实现；不改宿主 UI。
- macOS：保持通知 fail-fast，测试通过 fake gateway 运行；native SQLite 继续作为 ViewModel 集成环境。
- iOS：编译与 framework 链接验证 shared API；SwiftUI UI 测试需在 iOS 宿主工程补充。

## 文件拆分策略

- 通知 contract、orchestrator、Task ViewModel wiring 与测试分别置于独立文件。
- 单个核心文件不超过 1000 行；不会把平台调度细节回填进 orchestrator。
- 多个 Task ViewModel 的 lifecycle 调用保持小型、显式，不建立反射或隐式回调框架。

## 风险点

- 通知调度在数据库提交后执行；若平台调用失败，持久化数据不回滚，ViewModel 只显示错误并允许后续 refresh 恢复。
- 现有 actual 的简单时间计算不是本轮可证明的生产调度语义；本轮验收只覆盖共享决策与 API 调用。
- 三端 UI 测试不在这个仅含 `shared` module 的仓库中，必须跟随原生宿主集成另行验收。

## 模块拆分

- `contract`：
- `domain`：
- `presentation`：
- `infra`：

## 平台映射

- iOS：
- HarmonyOS：
- Android：

## 文件拆分策略

- 单个核心文件尽量不超过 1000 行有效代码
- 若必须逃逸，先申请开发者批准

## 风险点

- 
