# KMP Phase 1/2 Acceptance Module Plan

## 模块拆分

- `build-contract`：版本目录、Android 依赖、SQLDelight schema output 与 Gradle 验收任务。
- `native-actuals`：iOS 编译兼容性修复、macOS 平台 actual 完整性；不改变 common API。
- `test-acceptance`：共享测试 source set、iOS Simulator/macOS/Harmony 编译与链接命令，以及验收记录。

## 平台映射

- iOS：`shared/src/iosMain` 仅修复 Foundation/EventKit/UserNotifications API 的 Kotlin/Native 兼容性；共享 framework 使用 `linkDebugFrameworkIosSimulatorArm64` 验收。`iosSimulatorArm64Test` 是额外运行时验收，当前受 Kotlin/Native 测试链接器阻塞。
- HarmonyOS：`shared/src/ohosArm64Main` 保持 CPF `ohosArm64` K/N shared library 与 persistence C ABI；不编辑 ArkTS。
- Android：`shared/src/androidMain` 使用 AndroidX 依赖提供可编译的 WorkManager/ContextCompat actual。
- macOS：`shared/src/macosArm64Main` 为所有 common expect 提供安全的开发/测试 actual，以保持本机数据库集成测试目标。

## 文件拆分策略

- 单个核心文件尽量不超过 1000 行有效代码
- 若必须逃逸，先申请开发者批准
- macOS actual 按能力拆为独立文件；不将多个平台实现汇总到一个文件。

## 风险点

- Kotlin/Native 与 Apple framework 的签名随 Kotlin 版本变化，必须以实际编译器诊断为准。
- `Dispatchers.Main` 仍是 Phase 2 ViewModel 的运行时约束；本轮仅保证编译/测试，不承诺 ArkTS StateFlow 调度适配。
- Android WorkManager 必须接入真实依赖；Worker 的业务同步仍是未来 Service 层工作。
