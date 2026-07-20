# KMP Phase 1/2 Acceptance Tasks

## 阶段 1：规格冻结

- [x] 完成 `spec.md`
- [x] 完成 `module-plan.md`
- [x] 完成自检：验收限定为 Phase 1/2 已实现内容，排除宿主 UI/NAPI 产品功能。

## 阶段 2：基础设施

- [x] 配置 AndroidX Core 与 WorkManager 编译依赖
- [x] 配置 SQLDelight migration schema output
- [x] 修复 iOS actual 的 Kotlin/Native API 兼容性
- [x] 补齐 macOS actual

## 阶段 3：功能实现

- [x] 完成构建/actual 修复批次
- [x] 补充可复跑的 Phase 1/2 验收脚本与 iOS Simulator 状态说明
- [x] 大型改动完成后统一编译

## 阶段 4：验证与回写

- [x] 执行规格校验
- [x] 执行大文件扫描
- [x] 执行 Android、iOS、macOS、Harmony 与 migration 验收
- [x] 回写最终实现状态：iOS framework 已链接；`iosSimulatorArm64Test` 受 `_Kotlin_KonanStartStub` 工具链链接错误阻塞。
