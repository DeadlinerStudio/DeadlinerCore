# Memory User Boundary Tasks

## 阶段 1：规格冻结

- [x] 明确 Settings User 与 Memory User 的语义边界。
- [x] 冻结为直接迁移，不保留旧兼容路径。

## 阶段 2：基础设施

- [x] 增加 Memory User 独立表、查询与 migration。
- [x] 将画像读写收口到 MemoryRepository。

## 阶段 3：功能实现

- [x] 修改 MemorySnapshot 与 KmpMemoryStoreAdapter。
- [x] 更新 Android、iOS 与 OHOS 共享构造入口。
- [x] 添加边界与快照往返测试。

## 阶段 4：验证与回写

- [x] 运行 KMP 集成测试与 migration 校验。
- [x] 仅部署 Android AAR 并编译 Android。
- [x] 执行大文件扫描与规格校验。
- [x] 回写实现状态并定向提交。
