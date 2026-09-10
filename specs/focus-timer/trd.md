# Task Focus Timer TRD

## 文档信息

- 产品：Deadliner
- 功能：Task 专注计时与总时长
- 版本：TRD v0.2
- 状态：已冻结（Core 第一阶段）
- 初稿日期：2026-09-01
- 更新日期：2026-09-10
- 依赖：`prd.md`、`spec.md`、`module-plan.md`
- 核心仓库：`~/Codes/ProjectDeadliner/DeadlinerCore`
- Android：`~/Codes/ProjectDeadliner/Deadliner`
- iOS：`~/Codes/ProjectDeadliner/Deadliner-iOS`
- HarmonyOS：`~/Codes/ProjectDeadliner/DeadlinerNEXT`

## 1. 规格冻结时现状摘要

- 冻结前 KMP 已有独立 `Task` 聚合、`TaskStateMachine`、SQLDelight repository、ChangeLog/WebDAV、Snapshot v4、DeadlinerServer 与 Backup 链路。
- `TaskRepository` 是持久化 Task 状态迁移的权威，但当前 `applyAction` 不感知活动计时。
- 当前没有专注会话、活动计时、统一计时状态机或计时通知端口。
- 冻结前 Snapshot 根合同为 `formatVersion = 4`，Server entity kind 尚无 `focus_session`；Core 第二批已升级为 Snapshot v5 并补齐该资源。
- MVP 已收敛为 Task-only；Habit 不进入本轮合同、schema、同步或 bridge。

## 2. 技术目标

1. 在 commonMain 建立唯一 Task 专注计时状态机与时长计算权威。
2. 将“已完成且同步的 FocusSession”与“本机 local-only 的 ActiveFocusTimer”分离。
3. 所有启动命令在事务中校验一个 Active Task。
4. 所有结束、自动到点和恢复路径幂等地产生最多一条完成会话。
5. `FinishAndComplete` 原子地结束会话并将 Task 转为 `COMPLETED`。
6. 任何其他 Task 状态入口不得让已非 Active 的 Task 继续计时。
7. 总时长通过 query 聚合，不向 Task 冗余写入累计字段。
8. 已完成会话完整接入现有同步、服务器与备份链路。

## 3. 架构决策：Task-only，不预埋多态目标

MVP 使用 `taskUid`，不使用 `targetType + targetUid`：

- 当前只有一个已确认目标类型，多态引用会增加无效分支和孤儿校验复杂度。
- Habit 是否需要计时、是否应映射 HabitRecord 尚未确定。
- 如果 Habit 后续立项，再通过独立 migration 增加目标类型或单独聚合；不让未经验证的未来需求污染首版合同。

`focus_session` 名称保持通用，但其 v1 领域与数据库合同只允许 Task。

## 4. 目标架构

```text
Platform UI / lifecycle / notification callback / Task action entry
                              |
                              v
                       FocusTimerViewModel
                              |
                              v
                       FocusTimerService
             +----------------+----------------+
             |                |                |
        TaskResolver      TimerEngine      FocusAlarmPort
             |                |                |
             +--------- transaction -----------+
                         /            \
             ActiveTimerStore    FocusSessionRepository
                (local-only)       (sync + backup)
                                         |
                             Snapshot / WebDAV / Server
```

平台层可以每秒触发 UI refresh，但不得自行决定持久化 elapsed、到点完成、Task 可用性或总时长。

## 5. 领域合同

### 5.1 已完成会话

```kotlin
@Serializable
data class FocusSession(
    val uid: String,
    val taskUid: String,
    val taskTitleSnapshot: String,
    val mode: FocusTimerMode,
    val plannedDurationSeconds: Long? = null,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Long,
    val completionKind: FocusCompletionKind,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val isDeleted: Boolean = false,
)

@Serializable
enum class FocusTimerMode { STOPWATCH, COUNTDOWN }

@Serializable
enum class FocusCompletionKind {
    FOCUS_ONLY,
    FOCUS_AND_TASK_COMPLETED,
    FOCUS_AND_TASK_CHANGED,
    COUNTDOWN_ELAPSED,
    TASK_BECAME_INACTIVE,
}
```

`completionKind` 用于解释会话如何结束，不参与时长聚合，也不替代 Task 当前状态。

校验：

- `taskUid` 与 `taskTitleSnapshot.trim()` 非空。
- 时间戳为 UTC ISO-8601 Instant。
- 用户更正后的 `durationSeconds` 在 `1..min(86_400, wallClockSpanSeconds)`。
- `COUNTDOWN` 必须有 `plannedDurationSeconds in 60..10_800`。
- `STOPWATCH` 的 planned duration 必须为 null。
- `endedAt >= startedAt`；正常完成要求 `durationSeconds <= wallClockSpanSeconds`。
- 系统回拨造成的异常活动计时不直接生成不合法会话，而进入待处理状态。

FocusSession 是独立同步实体，不嵌套到 Task 聚合，避免新增会话导致整个 Task 聚合的 LWW 覆盖。

### 5.2 本机活动计时

```kotlin
data class ActiveFocusTimer(
    val sessionUid: String,
    val taskUid: String,
    val taskTitleSnapshot: String,
    val mode: FocusTimerMode,
    val plannedDurationSeconds: Long?,
    val state: FocusTimerState,
    val startedAt: String,
    val runningSegmentStartedAt: String?,
    val accumulatedSeconds: Long,
    val updatedAt: String,
)

enum class FocusTimerState { RUNNING, PAUSED }
```

- `sessionUid` 在启动时生成，完成会话复用该 UID，作为幂等键。
- `accumulatedSeconds` 只保存已封口运行片段；RUNNING 当前片段由 `runningSegmentStartedAt..now` 派生。
- ActiveFocusTimer 不进入 Snapshot、ChangeLog、Server 或 Backup，仅存本机数据库。

## 6. 命令与结果

```kotlin
sealed interface FocusTimerCommand {
    data class Start(
        val taskUid: String,
        val mode: FocusTimerMode,
        val plannedDurationSeconds: Long? = null,
    ) : FocusTimerCommand
    data object Pause : FocusTimerCommand
    data object Resume : FocusTimerCommand
    data object Finish : FocusTimerCommand
    data object FinishAndCompleteTask : FocusTimerCommand
    data object Discard : FocusTimerCommand
    data object Reconcile : FocusTimerCommand
}

enum class FocusTimerOutcome {
    APPLIED,
    NO_OP,
    TASK_REQUIRED,
    TASK_NOT_FOUND,
    TASK_NOT_ACTIVE,
    TIMER_ALREADY_ACTIVE,
    TIMER_NOT_FOUND,
    TIMER_REQUIRES_RESOLUTION,
    INVALID_STATE,
    INVALID_DURATION,
    CLOCK_ANOMALY,
}
```

结果返回 `FocusSession?`、`Task?` 和新的 `FocusTimerSnapshot?`。平台 UI 不从异常文本推断业务状态。

会话更正使用独立命令：

```kotlin
sealed interface FocusSessionCommand {
    data class AdjustDuration(val sessionUid: String, val durationSeconds: Long) : FocusSessionCommand
    data class Delete(val sessionUid: String) : FocusSessionCommand
}
```

不提供 `ChangeTask` 或 `CreateManualSession`。

## 7. 计时状态机

| 当前状态 | Start | Pause | Resume | Finish | FinishAndComplete | Discard | Reconcile 到点 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| NONE | RUNNING | NO_OP | NO_OP | NO_OP | NO_OP | NO_OP | NO_OP |
| RUNNING | ALREADY_ACTIVE | PAUSED | NO_OP | COMPLETED | COMPLETED + Task COMPLETED | NONE | Countdown→COMPLETED |
| PAUSED | ALREADY_ACTIVE | NO_OP | RUNNING | COMPLETED | COMPLETED + Task COMPLETED | NONE | 不推进 |

前提：Start/Resume 只有在绑定 Task 仍为 `ACTIVE` 时成功。Finish 允许保存 Task 刚刚变为非 Active 前已经发生的时间，但完成后不能再继续。

### 7.1 Elapsed 计算

```text
sealed = accumulatedSeconds
live = max(0, floor(now - runningSegmentStartedAt))  // only RUNNING
elapsed = sealed + live
displayElapsed = countdown ? min(elapsed, planned) : elapsed
remaining = countdown ? max(0, planned - elapsed) : null
```

- 同一进程的 UI tick 优先基于 monotonic clock，避免修改系统时间造成跳动。
- 持久化命令使用 `Clock.System` Instant 封口当前片段，使跨进程恢复可重建。
- `now < runningSegmentStartedAt` 返回 `CLOCK_ANOMALY`，live 按 0 处理并记录不含用户内容的诊断。

### 7.2 倒计时提前结束与自然到点

- `Finish`/`FinishAndCompleteTask` 提前执行：记录当时实际有效时长。
- 自然到点：`durationSeconds = plannedDurationSeconds`，Task 保持 Active。
- 自然到点的 `endedAt = runningSegmentStartedAt + (planned - accumulated)`。
- 在同一数据库事务中 `INSERT OR IGNORE focus_session(uid=sessionUid)` 并删除 active row。
- 通知回调、App resume、UI tick 均可调用 Reconcile；相同 UID 与事务保证幂等。

## 8. Task 状态协作

### 8.1 启动与继续

```kotlin
interface FocusTaskResolver {
    fun resolve(taskUid: String): Task?
}
```

- Start 与 Resume 在写入前再次读取 Task。
- 只有 `TaskState.ACTIVE && !isDeleted` 可启动或继续。
- Task 标题只在启动时写入 snapshot；展示时优先读取当前标题。

### 8.2 `FinishAndCompleteTask` 原子操作

事务顺序：

1. 读取并锁定当前 active timer 语义状态。
2. 确认绑定 Task 仍为 Active，使用 `TaskStateMachine.apply(ACTIVE, MARK_COMPLETE)` 得到目标状态。
3. 封口运行片段并校验有效时长至少 1 秒。
4. 插入 FocusSession，`completionKind = FOCUS_AND_TASK_COMPLETED`。
5. 删除 active row。
6. 更新 Task state/completedAt/updatedAt。
7. 为 `focus_session` 和 `task` 分别追加 changelog。
8. 事务提交后取消通知并触发一次 mutation notifier。

任一步失败都回滚，不允许“Task 已完成但会话未保存”或反向半完成。

### 8.3 其他 Task action 入口

`TaskRepository.applyAction` 在将绑定 Task 从 Active 变为其他状态前检查 active timer：

- 没有绑定活动计时：沿用现有行为。
- 存在绑定活动计时：返回 `TIMER_REQUIRES_RESOLUTION`，不改变 Task。
- UI、Widget、Watch、AI 等调用方收到该结果后不得绕过；交互界面确认后调用 FocusTimerService 的组合命令。
- `MARK_COMPLETE` 对应 `FinishAndCompleteTask`。
- 归档、放弃、删除使用“FinishAndApplyTaskAction”内部通用编排，保存会话后再执行原动作；首版 UI 可只公开需要的动作。

为避免在 repository 内复制 Focus 规则，建议从 `TaskRepository` 提取不提交事务、不通知的内部 `applyActionInTransaction`，供普通入口与组合用例复用。

### 8.4 同步导致 Task 失活

远端 Task 变更可能绕过本机确认。每次 Task sync apply 后及 App foreground Reconcile：

- 如果 active timer 的 Task 已非 Active，立即以本机感知时刻封口并生成 `TASK_BECAME_INACTIVE` 会话。
- 清除 active row，取消到点通知，不允许 Resume。
- UI 提示“任务状态已在其他位置变化，计时已结束”，允许用户向下更正时长。
- 由于跨设备传播延迟无法知道精确停止秒数，记录诊断事件；不使用远端 `updatedAt` 直接裁剪，避免设备时钟偏差造成负值。

## 9. 数据库设计

### 9.1 `focus_session`

```sql
CREATE TABLE focus_session (
    uid TEXT NOT NULL PRIMARY KEY,
    task_uid TEXT NOT NULL,
    task_title_snapshot TEXT NOT NULL,
    mode TEXT NOT NULL,
    planned_duration_seconds INTEGER,
    started_at TEXT NOT NULL,
    ended_at TEXT NOT NULL,
    duration_seconds INTEGER NOT NULL,
    completion_kind TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    is_deleted INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_focus_session_task
ON focus_session(is_deleted, task_uid, started_at);

CREATE INDEX idx_focus_session_started
ON focus_session(is_deleted, started_at);
```

不为 `task_uid` 建 foreign key：产品要求 Task 删除后保留历史，且 Snapshot/Server 可能在部分拉取时先接收会话。Repository 负责启动时引用完整性；历史允许孤儿引用并使用标题快照展示。

### 9.2 `active_focus_timer`

```sql
CREATE TABLE active_focus_timer (
    singleton_id INTEGER NOT NULL PRIMARY KEY CHECK (singleton_id = 1),
    session_uid TEXT NOT NULL UNIQUE,
    task_uid TEXT NOT NULL,
    task_title_snapshot TEXT NOT NULL,
    mode TEXT NOT NULL,
    planned_duration_seconds INTEGER,
    state TEXT NOT NULL,
    started_at TEXT NOT NULL,
    running_segment_started_at TEXT,
    accumulated_seconds INTEGER NOT NULL,
    updated_at TEXT NOT NULL
);
```

singleton 约束保证一台设备至多一个活动计时。该表是 local-only。

### 9.3 Queries

- `selectByUid`、`selectAllIncludingDeleted`。
- `selectForTask(taskUid)`。
- `sumDurationForTask(taskUid)`。
- `sumDurationStartedInRange(startInclusive, endExclusive)`。
- `selectRecent(limit)`。
- `insertSession`、`updateDuration`、`softDeleteSession`。
- active timer 的 `selectActive`、`upsertActive`、`deleteActive`。

今日统计按 `started_at` 归属会话。本地日边界由当前时区转为 `[startInclusive, endExclusive)` UTC Instant；跨午夜会话首版不拆分，Task all-time 总时长始终精确。

## 10. Repository 与应用服务

### 10.1 FocusSessionRepository

- 管理完成会话 CRUD、聚合 query、ChangeLog 与 mutation notifier。
- `completeFromActive` 与 `deleteActive` 必须处于同一事务。
- `AdjustDuration` 只允许向下更正，更新 `updatedAt` 并追加 UPDATE change。
- `Delete` 软删除并追加 DELETE change。

### 10.2 ActiveFocusTimerStore

- 只封装 singleton 本地表，不产生 changelog。
- 所有方法保持同步、短事务，不持有 UI coroutine scope。

### 10.3 FocusTimerService

- 接受命令与注入的 wall/monotonic clock，支持确定性测试。
- 编排 TaskResolver、TimerEngine、repositories、Task transaction mutation 和 FocusAlarmPort。
- 数据库事务成功后再安排/取消通知；通知失败不回滚时间事实，但返回 warning。
- 服务启动、App foreground、Task sync apply 后先 Reconcile，再发布状态。

## 11. 通知端口

```kotlin
interface FocusAlarmPort {
    fun scheduleCountdown(sessionUid: String, fireAt: String, taskTitle: String): FocusAlarmResult
    fun cancelCountdown(sessionUid: String): FocusAlarmResult
}
```

- Start/Resume 倒计时：根据剩余有效时长安排一次通知。
- Pause：取消。
- Finish/FinishAndComplete/Discard/自动结束：取消。
- App 恢复：Reconcile 后按活动状态重建或取消。
- Task title 可以用于通知正文，但平台日志不得记录其明文。

Android 可使用 foreground service + alarm；iOS 使用 local notification，Live Activity 作为可选展示；HarmonyOS 使用 Reminder。三端均不得依赖通知回调作为唯一完成路径。

## 12. 展示状态与刷新

```kotlin
data class FocusTimerSnapshot(
    val sessionUid: String,
    val taskUid: String,
    val taskTitle: String,
    val mode: FocusTimerMode,
    val state: FocusTimerState,
    val elapsedSeconds: Long,
    val remainingSeconds: Long?,
    val plannedDurationSeconds: Long?,
    val canPause: Boolean,
    val canResume: Boolean,
    val canFinish: Boolean,
    val canFinishAndComplete: Boolean,
    val warning: FocusTimerWarning?,
)
```

- ViewModel 每秒调用纯 `TimerEngine.snapshot(active, now)`，不每秒写数据库。
- Pause/Resume/Finish/生命周期落盘时才写 active row。
- Task 标题优先读取当前实体，解析失败时使用 snapshot title。
- 首发统计由 SQL query 返回 `todayDuration`、`taskTotalDuration`、`recentSessions`，三端不扫描全量会话。
- 周/月和分组统计接口推迟到第二阶段，不阻塞首发 schema。

## 13. 同步、Snapshot 与备份

### 13.1 Snapshot v5

```kotlin
@Serializable
data class SnapshotV3FocusSessionItem(
    val uid: String,
    val ver: SnapshotV3EntityVersion,
    val deleted: Boolean,
    val doc: FocusSession? = null,
)
```

保留历史 `SnapshotV3Root` 类型名，将 `FORMAT_VERSION` 从 4 升到 5，并新增：

```kotlin
@SerialName("focus_sessions")
val focusSessions: List<SnapshotV3FocusSessionItem> = emptyList()
```

新增 v4→v5 兼容器：旧根补空数组并提升 format version；v3 输入先进入现有 v4 迁移再进入 v5。

### 13.2 ChangeLog/WebDAV

- table/entity name 固定为 `focus_session`。
- INSERT/UPDATE payload 为完整 FocusSession；DELETE payload null。
- ChangeLog sync payload format 从 4 升至 5，显式拒绝未知未来版本。

### 13.3 DeadlinerServer

- 新增 entity kind `focus_session`。
- 每个 FocusSession 是独立 server entity mutation。
- pull partial snapshot 中 FocusSession 在 Task 后应用；Task 缺失时仍保留会话。
- Server scope state/outbox 使用现有通用表，不新增专用状态表。

### 13.4 冲突语义

- UID 由启动设备生成。
- 同 UID 的时长修改与删除沿用 `ts/ctr/dev` LWW。
- 不同设备产生不同 UID，全部保留并聚合求和。
- Task 删除不级联 FocusSession，也不视为同步验证错误。

### 13.5 Backup

BackupRoot 可保持 version 1，只要 snapshot codec 接受 v5；严格校验发生 breaking 时同步更新兼容测试。ActiveFocusTimer 不备份，恢复备份时必须清空 active row，避免保留指向旧数据集的计时。

## 14. 数据迁移

- 下一 SQLDelight schema migration 新建两张表，不修改既有 Task/Habit rows。
- 旧数据库迁移后两张表为空。
- 按仓库策略提供前版本 `.db` snapshot 与对应 `.sqm`，通过 `verifySqlDelightMigration`。
- Snapshot v3/v4 输入迁移为 v5 时 `focusSessions=[]`，不推断历史时长。
- 降级客户端无法理解 v5 时，阻止其覆盖已升级远端并提示升级。
- 若未来支持 Habit，再单独迁移；本轮没有 `target_type` 或 Habit 占位值。

## 15. 并发与一致性

- singleton active row 是同数据库互斥的最终防线；服务层使用 Mutex 串行化命令。
- 完成事务先 `INSERT OR IGNORE` 相同 UID，再删除 active row；重复调用返回已有 session 或 NO_OP。
- `FinishAndCompleteTask` 在一个 SQLDelight transaction 内写 session、active timer、Task 与两条 changelog。
- Task mutation 遇到绑定 active timer 时只返回 `TIMER_REQUIRES_RESOLUTION`，不得偷偷完成 Task。
- 事务提交后、通知取消前崩溃：恢复时无 active row，清理陈旧通知。
- 必须先提交 active row 再安排通知，避免只有通知没有计时事实。
- mutation notifier 只在业务事务成功后触发一次。

## 16. 测试策略

### 16.1 Common unit tests

- 状态机全部合法/非法转换。
- Stopwatch/countdown 在运行、暂停、继续、多段运行下的 elapsed。
- 提前结束记录实际值，自然到点记录计划值。
- 0 秒、1 秒、12h 警告、墙上时钟回拨。
- Task 全状态启动/继续矩阵。
- Finish 与 FinishAndComplete 的 Task 状态差异。
- 今日边界、时区、DST 与最近会话排序。

### 16.2 SQLDelight integration tests

- singleton 互斥、完成事务、重复 Reconcile 幂等。
- FinishAndComplete 成功与注入失败时的完整回滚。
- TaskRepository 对 active timer 返回 `TIMER_REQUIRES_RESOLUTION`。
- 修改/软删除后的聚合，Task 删除后历史保留。
- migration 与数据库重开恢复。

### 16.3 Sync tests

- Snapshot v4→v5、v5 round-trip 与 validator。
- ChangeLog/WebDAV encode/decode。
- Server mapper、不同 UID 合并、同 UID LWW、删除 tombstone。
- 远端 Task 失活触发本机计时 Reconcile。
- Backup export/import 不包含 active timer。

### 16.4 平台验收

- 前台、后台、锁屏、强杀、设备重启、通知拒绝。
- 倒计时到点通知重复/缺失。
- 从 Task 详情、全局选择器、通知点击进入。
- 从 Task 列表、Widget、Watch、AI 完成正在计时的 Task 时的确认/阻断。
- 手动修改系统时间和时区。

## 17. 性能与可观测性

- `focus_session` 按 task_uid 与 started_at 建索引；总时长使用 SQL SUM。
- UI 每秒只做纯内存计算，不每秒写盘、发日志或触发同步。
- 日志允许记录 session UID 哈希、状态迁移、duration bucket、outcome；禁止记录 Task title/note。
- 对 `CLOCK_ANOMALY`、重复自动完成、通知失败、孤儿 task 和远端失活停止记录计数型诊断。

## 18. 文件拆分与交付顺序

建议 shared 文件：

- `domain/focus/FocusModels.kt`
- `domain/focus/FocusTimerEngine.kt`
- `domain/focus/FocusTaskPolicy.kt`
- `db/repository/focus/FocusSessionRepository.kt`
- `db/repository/focus/ActiveFocusTimerStore.kt`
- `service/focus/FocusTimerService.kt`
- `platform/FocusAlarmPort.kt`
- `viewmodel/focus/FocusTimerViewModel.kt`
- `sqldelight/com/deadliner/db/FocusSession.sq`
- `sqldelight/com/deadliner/db/ActiveFocusTimer.sq`

Task 状态协作还会修改 `TaskRepository` 及所有消费 `TaskActionOutcome` 的 bridge。交付顺序：领域纯逻辑 → schema/repository → Task 组合事务 → 恢复/通知 → sync/backup → shared state → 三端 UI。完成一个大型批次后统一编译。

## 19. 已确认技术方向

1. MVP 使用 `taskUid`，不预埋 Habit 多态目标。
2. 会话允许向下更正并作为可变独立同步实体走 LWW，不采用 event sourcing。
3. 提前结束倒计时保存实际时长。
4. `FinishAndCompleteTask` 必须是原子组合事务。
5. 首发只提供今日、Task 累计和最近会话 query；复杂统计后置。

## 20. 第一阶段采用的技术默认值

1. 活动计时 local-only；跨设备只同步已完成 FocusSession。
2. 持久化使用 UTC Instant；同进程 UI 的单调时钟端口在平台展示批次接入。
3. Snapshot 后续保留 `SnapshotV3Root` 类型名，只提升 format 5。
4. 正计时 12 小时警告但不自动结束；恢复时超过 7 天进入异常待处理态。
5. 同步导致 Task 失活时使用本机感知时刻停止，并允许用户向下更正。
6. Task 标题快照首阶段保存现有完整标题；统一最大长度在平台表单契约接线前补齐。

Snapshot v5 与公开 `TaskActionOutcome` 新枚举值不进入第一批，避免在未提交 Widget 改动上直接叠加 breaking change。第二批开始前已建立包含 Widget 与 Focus 第一批的全工作区安全提交 `1d7a095`。

## 21. Core 第一批实现回写

- 已实现第 5～7 节的 Task-only 模型、计时计算和命令合同；运行中使用 UTC wall-clock 锚点，monotonic tick 留到平台展示批次。
- 已实现第 9～12 节的 v6→v7 表结构、repository、组合事务、通知端口、恢复收敛、统计 query 和共享 ViewModel。
- `FocusTimerViewModel.tick()` 正常路径只根据内存中的活动锚点计算展示值；命令或显式刷新后才更新统计 query。
- 今日边界由当前时区的相邻本地午夜转换为 UTC，已覆盖 DST 23 小时自然日测试。
- 第一批测试覆盖暂停时间排除、提前结束、自然到点、重启恢复、原子结束并完成、会话更正/删除、通知重排和轻量统计。
- 第 8 节的所有 Task 入口守门，以及第 13～16 节的 Snapshot v5、WebDAV、Server、Backup 和三端 bridge，等待全工作区 Git 安全检查点后进入下一批。

## 22. Core 第二批实现回写

- 安全检查点已创建：`1d7a095`。
- `FocusTaskPolicy`、`TaskRepository` 与共享 Task ViewModel 已接入 `TIMER_REQUIRES_RESOLUTION`；状态变更、删除与计时启动的检查和写入均位于数据库事务中。
- `FocusTimerService.resolveTaskAction` 与 `resolveTaskDeletion` 在确认后原子保存实际会话、清除活动计时并修改 Task。
- Snapshot 已提升至 v5；完成会话作为独立 `focus_session` 资源接入 WebDAV、DeadlinerServer 和 Backup，恢复备份会清除 local-only 活动计时。
- 第二批通过 SQLDelight migration verification、macOS ARM64 全量测试、规格校验与 diff whitespace 检查。
- 尚待平台批次：三端 bridge/UI、通知实现、生命周期接线、同进程 monotonic clock 和三端手工矩阵。
