# Task Focus Timer Tasks

## 阶段 1：规格冻结

- [x] 完成 Task-only `spec.md` v0.2 初稿。
- [x] 完成 `prd.md` v0.2，并记录已确认产品决策与 Habit 探索问题。
- [x] 完成 Task-only `trd.md` v0.2。
- [x] 完成 `module-plan.md` v0.2。
- [x] 采用 TRD 第 20 节第一阶段技术默认值。
- [x] 将文档状态更新为“已冻结（Core 第一阶段）”。

## 阶段 2：基础设施

- [x] 在 breaking schema/sync change 前创建全工作区 Git 安全提交 `1d7a095`。
- [x] 增加 Focus 领域合同、状态机、聚合规则与 common tests。
- [x] 增加独立 `FocusTaskPolicy`，供 Task 入口守门复用。
- [x] 增加 `focus_session`、`active_focus_timer` schema、queries、migration 与数据库测试。
- [x] 提取可在组合事务中复用的 Task action mutation。
- [x] 为公开 Task action 结果新增 `TIMER_REQUIRES_RESOLUTION`，并迁移 Core Task ViewModel 消费者。
- [x] 实现 `FinishAndCompleteTask` 原子事务与一致性测试。
- [x] 增加 UTC wall-clock 锚点与到点通知端口，实现 `FocusTimerService`。
- [ ] 在平台展示批次接入同进程 monotonic clock。
- [x] 将完成会话接入 Snapshot v5、ChangeLog/WebDAV、DeadlinerServer 与 Backup。
- [x] 更新 Core API reference 与 v6→v7 migration 说明。
- [ ] 更新 Android/iOS/HarmonyOS bridge contract。

## 阶段 3：功能实现

- [x] 实现共享 Focus ViewModel 与首发统计 state：今日、Task 今日/累计、最近会话。
- [ ] Android 接入 Task 入口、时钟、常驻/到点通知、迷你条与轻量历史。
- [ ] iOS 接入 Task 入口、时钟、Live Activity/本地通知（能力允许时）、迷你条与轻量历史。
- [ ] HarmonyOS 接入 Task 入口、时钟、Reminder、迷你条与轻量历史。
- [ ] 所有 Task 状态入口接入 active timer 解决流程。
- [ ] 接入应用生命周期、进程重启及远端 Task 失活恢复。
- [ ] 按 PRD 验收样例完成三端手工验收。
- [ ] 完成一个大型实现批次后统一编译，不对每个小 patch 重复编译。

## 阶段 4：验证与回写

- [x] 运行 common unit tests、macOS SQLDelight integration tests 和 migration verification。
- [ ] 运行 Android、iOS、HarmonyOS 目标编译；HarmonyOS 命令显式设置 `DEVECO_HOME`。
- [ ] 执行三端后台、强杀、重启、时间回拨/前跳与通知权限矩阵。
- [x] 执行 Snapshot v4→v5、WebDAV/Server 往返与备份恢复测试。
- [ ] 执行远端 Task 失活恢复及三端冲突手工测试。
- [x] 执行 Core 两批核心大文件等价扫描；新增/修改的 Focus 核心文件均小于 1000 行。
- [x] 执行 Core 两批规格校验。
- [x] 回写 Core 第一批模块映射、实际文件和验证结果。
- [x] 运行 Core 第二批 macOS ARM64 tests 与 migration verification。

## 后续阶段：分析与 Habit 探索

- [ ] 基于首发数据评估本周/本月趋势与 Task/Category 分布优先级。
- [ ] 设计自定义日期筛选和完成前专注次数指标。
- [ ] 调研 Habit 用户需求：投入记录 vs 按时长完成。
- [ ] 只有 Habit 产品语义确认后，另建 PRD/TRD 与数据迁移计划。
