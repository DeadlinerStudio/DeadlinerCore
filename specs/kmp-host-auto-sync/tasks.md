# KMP Host Build Auto Sync Tasks

## 阶段 1：规格冻结

- [x] 完成 `spec.md`
- [x] 完成 `module-plan.md`
- [x] 完成评审或自检

## 阶段 2：基础设施

- [x] 确认 Core 发布脚本、三端制品路径和路径覆盖接口

## 阶段 3：功能实现

- [x] 接入 Android Gradle 构建钩子
- [x] 接入 iOS Xcode 构建钩子
- [x] 接入 Harmony Hvigor 构建钩子
- [x] 大型改动完成后再统一编译

## 阶段 4：验证与回写

- [x] 执行规格校验
- [x] 执行大文件扫描（本仓库未提供说明中引用的脚本，改以变更文件行数检查）
- [x] 回写最终实现状态

## 验证记录

- [x] Android `:app:assembleDebug` 成功，日志显示 `:app:syncDeadlinerCoreKmp` 先构建并字节校验 AAR。
- [x] HarmonyOS `assembleHap` 成功；`syncDeadlinerCoreKmp` 已作为 `default@PreBuild` 的前置任务执行，Core SO 与 `entry/libs` 哈希一致。
- [x] iOS 构建阶段已由 Xcode 识别；同一脚本内容成功构建、规范化并部署 XCFramework，Core 与 `Vendor/KMP` 目录时间一致。
- [!] 完整 iOS App 构建仍受既有 `actool` 的空资源对象错误阻断，错误发生在资源编译阶段，早于本次同步逻辑的可用性验证。
