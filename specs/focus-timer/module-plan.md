# Task Focus Timer Module Plan

## 模块拆分

- `focus-contract`：`FocusSession`、`ActiveFocusTimer`、Task-only 命令、结果与展示状态。
- `focus-domain`：Task 可用性规则、计时状态机、经过时长计算、倒计时收敛和聚合规则。
- `focus-infra`：SQLDelight queries、完成会话 repository、本机活动计时存储、Snapshot/ChangeLog/Server 映射。
- `focus-application`：`FocusTimerService` 编排跨 Focus/Task 的原子事务、时钟与通知端口。
- `focus-presentation`：共享 ViewModel/State 与三端时钟页、Task 入口、迷你计时条和轻量历史。
- `focus-analytics-v2`：后续周/月趋势、按 Task/Category 分布与筛选；不进入首发实现。

## 平台映射

- KMP Core：
  - 合同与规则：`shared/src/commonMain/kotlin/com/deadliner/domain/focus`
  - 应用服务：`shared/src/commonMain/kotlin/com/deadliner/service/focus`
  - 数据访问：`shared/src/commonMain/kotlin/com/deadliner/db/repository/focus`
  - SQL：`shared/src/commonMain/sqldelight/com/deadliner/db/FocusSession.sq`、`ActiveFocusTimer.sq` 与下一版本 migration
  - Task 协作：`TaskRepository` 与所有 `TaskActionOutcome` bridge
  - 同步：`shared/src/commonMain/kotlin/com/deadliner/sync/{snapshot,server}` 的独立 `focus_session` 资源
  - 展示状态：`shared/src/commonMain/kotlin/com/deadliner/viewmodel/focus`
- Android（`~/Codes/ProjectDeadliner/Deadliner`）：
  - `data/focus`：Core 适配、foreground service/alarm/notification gateway
  - `ui/focus`：全屏时钟、迷你计时条、轻量历史与 Task 入口
  - Task/Widget 入口消费 `TIMER_REQUIRES_RESOLUTION`
- iOS（`~/Codes/ProjectDeadliner/Deadliner-iOS`）：
  - `Core/Application/UseCases/Focus`：KMP bridge 编排
  - `Data/Focus`：本地通知和生命周期适配
  - `Features/Focus`：SwiftUI 时钟、轻量历史、Task 入口
- HarmonyOS（`~/Codes/ProjectDeadliner/DeadlinerNEXT`）：
  - `entry/src/main/ets/model/focus`：bridge DTO
  - `entry/src/main/ets/common/repository/focus`：Core 与 Reminder 适配
  - `entry/src/main/ets/pages/routes/focus`：时钟和轻量历史
  - `entry/src/main/ets/pages/components/focus`：迷你计时条与 Task 时间摘要

## 文件拆分策略

- 领域模型、状态机、Task policy、SQL repository、同步 mapper 和 ViewModel 分文件维护。
- `FocusTimerService` 只负责编排；纯时间计算放入 `FocusTimerEngine`。
- Task 状态转换继续复用 `TaskStateMachine`，组合事务不复制状态规则。
- Snapshot store/server mapper 只增加路由，不实现计时规则。
- 分析 v2 使用独立 query/state 文件，不把趋势统计塞入首发 ViewModel。
- 单个核心文件尽量不超过 1000 行有效代码；若必须逃逸，先申请开发者批准。

## 风险点

- “结束并完成”横跨 FocusSession 与 Task 两个聚合，需要事务内复用 Task transition。
- 所有 Task 完成/归档/放弃/删除入口都要处理 active timer，漏接会破坏“不对非 Active Task 继续计时”。
- 活动计时 local-only；远端 Task 失活只能在本机感知时停止，存在同步延迟，需要允许时长更正。
- Snapshot v5、ChangeLog payload 和 Server entity kind 必须同批发布。
- 三端若自行计算 elapsed/remaining，容易漂移；展示必须消费 shared 锚点与规则。
- Habit 不在本轮 schema 中预留；若后续立项，必须走独立产品与 migration 评审。

## Core 第一批实际落点

- `domain/focus/FocusModels.kt`、`FocusTimerEngine.kt`：Task-only 合同、时长计算、暂停/继续、提前结束、自然到点和异常警告。
- `db/repository/focus/` 与 `FocusSession.sq`、`ActiveFocusTimer.sq`、`migrations/6.sqm`：完成会话、local-only singleton、轻量聚合 query 与 v6→v7 migration。
- `service/focus/FocusTimerService.kt`、`platform/FocusAlarmPort.kt`：命令串行化、恢复收敛、到点通知端口和原子“结束并完成”。
- `viewmodel/focus/FocusTimerViewModel.kt`：纯计算秒级 tick、今日摘要、Task 今日/累计、最近会话、更正和删除。
- `TaskRepository.kt`：提取事务内 Task action mutation；公开 active-timer 守门结果等待下一 breaking 批次。
- 第一批未修改 Snapshot/WebDAV/Server/Backup 格式，也未接入 Android、iOS、HarmonyOS UI。

## Core 第二批实际落点

- `domain/focus/FocusTaskPolicy.kt` 与 `TaskRepository.kt`：在同一数据库事务内守住状态变更、软删除和计时启动之间的竞态。
- `TaskListViewModel.kt`、`TaskDetailViewModel.kt`、`ArchiveViewModel.kt`：向平台暴露 `TIMER_REQUIRES_RESOLUTION`，拦截时不提前更新内存状态或通知。
- `FocusTimerService.kt`、`FocusTimerViewModel.kt`：确认后原子保存会话并完成、放弃或删除绑定 Task。
- Snapshot 根合同提升至 v5；完成会话进入 WebDAV snapshot、DeadlinerServer 独立实体和 Backup，活动计时始终 local-only。
- 已覆盖 v4→v5 迁移、会话同步与 tombstone、Server 往返、备份恢复清除活动计时、Task 入口拦截和组合事务。
- Android、iOS、HarmonyOS bridge 与 UI 仍在下一平台批次。
