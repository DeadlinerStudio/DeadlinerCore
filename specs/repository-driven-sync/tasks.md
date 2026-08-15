# Repository 驱动的 KMP 同步 Tasks

## 阶段 1：规格冻结

- [x] 完成 `spec.md`
- [x] 完成 `module-plan.md`
- [x] 完成评审或自检

## 阶段 2：基础设施

- [x] 确认共享依赖、接口、数据结构
- [x] 实现 mutation notifier 与数据库级去抖协调器
- [x] 扩展 V3 同步结果的资源种类

## 阶段 3：功能实现

- [x] 接入全部 KMP Repository 的事务后通知
- [x] iOS 移除业务写后原生同步并修复 Capture 刷新
- [x] HarmonyOS 移除业务写后原生同步并安装 KMP 自动同步配置
- [x] 大型改动完成后再统一编译

## 阶段 4：验证与回写

- [x] 补充去抖、pending rerun 与 Capture 跨设备测试
- [x] 执行规格校验
- [x] 执行大文件扫描
- [x] 执行 KMP macOS/iOS/HarmonyOS 编译验证
- [x] 回写最终实现状态

## 验证记录

- `RepositorySyncCoordinatorTest` 与 `DatabaseIntegrationTest` 通过。
- OHOS Kotlin 编译及 release shared library 链接通过；部署后的 so 包含自动同步配置符号。
- HarmonyOS C++、ArkTS 与 HAP 打包通过，最终签名步骤因本机 keystore/JDK 算法失败。
- Apple XCFramework release 双切片构建通过，Swift 最小桥接类型检查通过；完整 iOS build 被 Xcode `actool` 内部异常拦截。
