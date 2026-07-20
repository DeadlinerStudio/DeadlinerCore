# KMP Phase 1/2 Acceptance

## 背景

- 创建日期：2026-07-20
- 功能标识：`kmp-phase12-acceptance`
- 需求来源：将 MIMO 已完成的 Phase 1（平台抽象）和 Phase 2（KMP ViewModel）变为可完整编译、可在 iOS Simulator 验证的基线。

## 目标

- 恢复 `shared` 的 Android、iOS Simulator、macOS ARM64 和 HarmonyOS ARM64 编译/链接矩阵。
- 使 SQLDelight migration verification 可在干净工作区运行。
- 为已有 Domain、Snapshot 和 SQLite 持久化测试提供可执行的 iOS Simulator 验收入口，并记录测试可执行文件的工具链状态。
- 明确 HarmonyOS 当前只验收 native persistence bridge；ArkTS/NAPI 业务适配不属于本轮完成定义。

## 非目标

- 不实现新的产品功能或 UI。
- 不把当前占位的通知、日历、权限、后台任务宣称为生产可用实现。
- 不在本仓库创建 ArkTS 页面或 NAPI 模块；它们属于 HarmonyOS 宿主工程。
- 不引入 DI 框架、网络同步传输或 Compose/SwiftUI UI 层。

## 用户场景

1. 开发者在 Apple Silicon Mac 上运行统一 Gradle 验收命令；共享领域、快照和数据库契约能编译，iOS Simulator 可执行共享测试。
2. HarmonyOS 宿主可获取经 CPF 工具链链接的 `libDeadlinerCore.so`，并保留已有 C persistence ABI。

## 验收标准

- `:shared:compileDebugKotlinAndroid` 成功。
- `:shared:compileKotlinIosSimulatorArm64` 与 `:shared:linkDebugFrameworkIosSimulatorArm64` 成功，产物可供 iOS 宿主集成。
- `:shared:iosSimulatorArm64Test` 能执行；若 Kotlin/Native 测试可执行文件不能由本机工具链链接，必须记录精确诊断，不能将其误记为应用框架失败。
- `:shared:macosArm64Test` 成功，作为本机 SQLite integration verification。
- `:shared:linkDebugSharedOhosArm64` 成功。
- `:shared:verifySqlDelightMigration` 成功。
- 未实现能力必须显式标识为 unsupported/no-op，不得静默伪造成功结果或丢失数据。

## 风险与约束

- 当前仓库只包含 `shared` Gradle module；原生 app 宿主构建和设备部署不在本轮可验证范围内。
- iOS Simulator 测试依赖本机 Xcode simulator runtime；如果环境缺失，记录为环境阻塞而非代码通过。
- 当前 CPF Kotlin/Native 2.2.21-0.3.0 在本机 Xcode 26.5 上链接 `iosSimulatorArm64Test` 可执行文件时缺失 `_Kotlin_KonanStartStub`。共享 framework 已成功链接，因此这是测试运行时/工具链阻塞，不是 Phase 1/2 共享 framework 的编译阻塞。
- CPF 依赖在配置阶段解析 CInterop；需维持现有 Harmony 静态 SQLite 链接设置。
