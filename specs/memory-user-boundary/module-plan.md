# Memory User Boundary Module Plan

## 模块拆分

- Memory domain：`MemorySnapshot.userProfile` 表示独立画像文本。
- Memory persistence：`MemoryRepository` 管理画像表及 Memory fragments。
- LiFi adapter：`KmpMemoryStoreAdapter` 只依赖 MemoryRepository。
- Platform composition：Android/HarmonyOS 构造入口移除 Settings profile repository 注入。

## 平台映射

- KMP：`shared/src/commonMain` 领域、仓储、SQLDelight 与 LiFi adapter。
- Android：`KmpV6Runtime` 仅调整共享对象装配。
- HarmonyOS：KMP OHOS composition 仅调整共享对象装配；不修改 ArkTS UI。
- iOS：当前不直接构造该 adapter，无原生改动。

## 文件拆分策略

- 独立新增 `MemoryUserProfile.sq` 和 migration，避免把查询塞入 UI/Profile 文件。
- 不创建新的平台 wrapper 或兼容 adapter。
- 所有核心文件保持低于 1000 行。

## 风险点

- SQLDelight migration 必须覆盖已存在的 v3 数据库。
- 构造函数移除参数会要求所有调用点一次性迁移。
- Memory snapshot 类型变化必须通过往返测试约束。
