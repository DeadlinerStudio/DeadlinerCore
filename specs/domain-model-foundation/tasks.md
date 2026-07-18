# Deadliner Shared Domain Model Foundation Tasks

## 阶段 1：规格冻结

- [x] 清点 iOS 当前领域与持久化模型。
- [x] 冻结 Task/Habit 解耦决策。
- [x] 完成规格与模块计划。

## 阶段 2：基础设施

- [x] 建立 commonMain domain package。
- [ ] 建立 iOS/Android JSON fixture export task。

## 阶段 3：功能实现

- [x] 实现 Task、Habit、Category、Sync、Memory、AI proposal 与 Account 模型。
- [x] 实现 Task 状态机与同步版本比较。

## 阶段 4：验证与回写

- [ ] 编译 common/iOS/Android targets（阻塞：仓库尚未包含 Gradle wrapper，环境也没有全局 Gradle）。
- [ ] 运行领域模型单测（同上）。
- [ ] 回写真实构建与测试结果。
