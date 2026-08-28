# Unified Mobile Logging Tasks

## 阶段 1：规格冻结

- [x] 完成 `spec.md`
- [x] 完成 `module-plan.md`
- [x] 完成 iOS 格式与现有直连调用自检

## 阶段 2：基础设施

- [x] 确认共享依赖、接口、数据结构
- [x] 实现 Android 文件日志封装和启动初始化
- [x] 实现 HarmonyOS 文件日志封装和启动初始化

## 阶段 3：功能实现

- [x] 替换 Android 全部直连 `Log`
- [x] 替换 HarmonyOS 全部直连 `console` / `hilog`
- [x] 覆盖 KMP 桥接、迁移、同步与 AI 的稳定事件
- [x] 在两端设置/反馈入口增加诊断日志导出
- [x] 大型改动完成后再统一编译

## 阶段 4：验证与回写

- [x] 执行规格校验
- [x] 执行大文件扫描
- [x] 回写最终实现状态

## 最终验证记录

- [x] `:shared:compileReleaseKotlinAndroid` 与 `:shared:compileKotlinOhosArm64` 成功
- [x] Android `:app:assembleDebug` 成功
- [x] HarmonyOS `assembleHap` 成功（使用 DevEco bundled Node 24）
- [x] Android、HarmonyOS 主源码直连日志扫描无结果；唯一系统日志调用位于各自 `AppLog` 封装
