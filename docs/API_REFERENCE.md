# DeadlinerCore API Reference (Preview)

`DeadlinerCore` is the KMP domain, SQLite persistence, change-log synchronization, and snapshot contract for the NEW ERA preview. Its database is always named `deadliner_new_era.db`; it is isolated from v5 data.

This is an initial source-level reference for application integrators. All public models are `@Serializable`; timestamp fields are ISO-8601 strings unless the type explicitly uses an epoch-millisecond `Long`.

## Platform entry points

| Platform | Driver factory | Notes |
| --- | --- | --- |
| Android | `AndroidDatabaseDriverFactory(context)` | Example provider: `androidApp/src/main/kotlin/com/deadliner/android/NewEraDatabaseProvider.kt`. |
| iOS | `IosDatabaseDriverFactory()` | Uses SQLDelight `NativeSqliteDriver` and the native default app database location. |
| macOS Arm64 | `MacosDatabaseDriverFactory(databasePath)` | Intended for development and integration tests. |
| HarmonyOS Arm64 | `OhosDatabaseDriverFactory(databaseDirectory)` | `databaseDirectory` must be an absolute persistent path; no in-memory fallback exists. |

Create the façade once per app/database lifetime, and close it when the owning application scope ends:

```kotlin
val core = DeadlinerDatabase.create(IosDatabaseDriverFactory())
try {
    core.tasks.create(task)
} finally {
    core.close()
}
```

`DeadlinerDatabase` exposes `categories`, `tasks`, `habits`, `focusSessions`, `focusStatistics`, and `changeLog`. Business writes append a changelog record within the same SQL transaction. Completed focus sessions are independent Snapshot v5/WebDAV/DeadlinerServer/backup resources; the active timer remains device-local and is cleared during backup restore.

## Core build version

Hosts can read the version that was embedded into the linked KMP binary:

```kotlin
import com.deadliner.platform.DeadlinerCoreVersion

val version = DeadlinerCoreVersion.value        // e.g. "DeadlinerCore-20260830-01-main@ci"
val compileDate = DeadlinerCoreVersion.compileDate
val compileBranch = DeadlinerCoreVersion.compileBranch
val compileUserName = DeadlinerCoreVersion.compileUserName
val compiledAtUtc = DeadlinerCoreVersion.compiledAtUtc
```

The identifier always uses `DeadlinerCore-${compileDate(YYYYMMDD-HH)}-${compileBranch}@${compileUserName}`. By default it uses the UTC compile hour, the checked-out Git branch, and the local username. CI can override individual parts at compile time:

```bash
./gradlew :shared:assembleRelease \
  -PdeadlinerCoreCompileDate=20260830-01 \
  -PdeadlinerCoreCompileBranch=release/v12 \
  -PdeadlinerCoreCompileUserName=ci
./gradlew :shared:printDeadlinerCoreVersion
```

HarmonyOS callers can use `deadliner_persistence_core_version(output, outputCapacity)` with the existing two-call, caller-owned UTF-8 buffer convention; `deadliner_persistence_bridge_version()` retains the integer bridge ABI version for compatibility checks.

## Domain models

### Tasks

- `Task(uid, title, note, startAt, dueAt, state, completedAt, categoryUid, isStarred, calendarEventId, createdAt, updatedAt, isDeleted, subtasks)` is the task aggregate.
- `TaskSubtask(uid, taskUid, content, isCompleted, sortOrder, createdAt, updatedAt, isDeleted)` belongs to one `Task`.
- `TaskState`: `ACTIVE`, `COMPLETED`, `ARCHIVED`, `ABANDONED`, `ABANDONED_ARCHIVED`.
- `TaskStateMachine.apply`, `isNoOp`, `isVisibleInMainList`, and `isVisibleInArchive` provide the canonical transition/visibility rules.

`TaskRepository` methods:

```kotlin
core.tasks.list(): List<Task>
core.tasks.find(uid: String): Task?
core.tasks.create(task: Task)
core.tasks.update(task: Task)
core.tasks.delete(uid: String, updatedAt: String) // tombstones task and live subtasks
```

### Task / Habit focus timer (Core preview)

- `FocusTimerMode`: `STOPWATCH` or `COUNTDOWN` (1–180 minutes).
- `FocusTimerCommand`: `Start` (Task), `StartHabit`, `Pause`, `Resume`, `Finish`, `FinishAndCompleteTask`, `FinishAndRecordHabit`, `Discard`, and `Reconcile`.
- `FocusTimerSnapshot` is the client display contract. Clients render its elapsed/remaining values and capability flags rather than recreating timer rules.
- `FocusSession` is a completed, editable history row; `ActiveFocusTimer` is a device-local singleton and is never a cross-device resource.

Create one service for the app/database lifetime and inject a platform alarm adapter when countdown notifications are available:

```kotlin
val timer = core.createFocusTimerService(focusAlarmPort)
timer.execute(FocusTimerCommand.Start(taskUid, FocusTimerMode.COUNTDOWN, 25 * 60))
timer.execute(FocusTimerCommand.Pause)
timer.execute(FocusTimerCommand.Resume)
timer.execute(FocusTimerCommand.Finish)
timer.execute(FocusTimerCommand.FinishAndCompleteTask)
timer.execute(FocusTimerCommand.Discard)
timer.execute(FocusTimerCommand.Reconcile) // app start/foreground/notification callback
```

`FinishAndCompleteTask` writes the completed session, clears the local active timer, and marks the bound Task `COMPLETED` in one database transaction. A countdown reaching zero records its planned duration and leaves the Task active.

`StartHabit` binds the timer to a Habit and fixes its check-in date at the local start day. `FinishAndRecordHabit(sessionUid, amount, expectedHabitUpdatedAt)` accepts the explicit active or saved session UID, uses an actual positive amount for quantity tracking, and keeps session saving plus Habit progress atomic and idempotent. Countdown completion alone never checks in a Habit; a saved result can be checked in explicitly later. Non-scheduled days may be focused but cannot use this joint check-in action. A stale session command must never finish a different, newer active timer.

Completed-session queries and edits:

```kotlin
core.focusSessions.recent(limit)
core.focusSessions.recentForTask(taskUid, limit)
core.focusSessions.totalForTask(taskUid)
core.focusSessions.totalStartedInRange(startInclusive, endExclusive)
core.focusSessions.totalForTaskStartedInRange(taskUid, startInclusive, endExclusive)
core.focusSessions.adjustDuration(sessionUid, lowerDurationSeconds, updatedAt)
core.focusSessions.delete(sessionUid, updatedAt)
```

`FocusTimerViewModel` exposes the active timer, today duration/session count, typed selected target today/all-time duration, recent sessions, session adjustment/deletion, and explicit resolution of a state change or deletion while timing. The legacy `selectedTask*` duration/history fields also carry Habit results when `selectedTarget.kind == HABIT`; do not interpret an empty `taskUid` as an unbound session. Call `tick()` once per visible second; it recalculates display state from in-memory anchors and does not persist per-second updates.

Public Task mutation APIs return `TaskActionOutcome.TIMER_REQUIRES_RESOLUTION` when the requested state change or deletion targets the Task bound to the active timer. After user confirmation, route the operation through `FocusTimerService.resolveTaskAction(...)` or `resolveTaskDeletion(...)`; these save the elapsed session, clear the active timer, and mutate the Task in one transaction.

iOS clients can use `IosFocusTimerStateBridge` to receive typed `FocusTimerUiState` callbacks on the main thread while commands and reconciliation run in a worker scope. The bridge exposes Task/Habit stopwatch/countdown start, typed target/session selection, pause/resume, finish, “finish and complete”, `finishAndRecordHabit(sessionUid, amount, expectedHabitUpdatedAt)`, explicit Task/Habit resolution, history edits, refresh, dedicated foreground `reconcile()`, and a write-free visible-second tick. `mutateHabitIfFocusResolved(habitUid, delete, onResult)` catches the repository's active-timer guard before crossing into Swift and reports `TIMER_REQUIRES_RESOLUTION`; it does not finish the timer implicitly or replace the confirmed resolution command.

`FocusTimerViewModel.resolveHabitChange(habitUid, delete)` and its suspending `resolveHabitChangeNow` facade publish the result of `FocusTimerService.resolveHabitChange`. Invoke only after explicit archive/delete confirmation; the existing service atomically preserves elapsed focus and mutates the Habit. These are additive presentation APIs, not new Habit rules.

HarmonyOS keeps the legacy `deadliner_persistence_focus_state_json(selectedTaskUid, output, capacity)` ABI. The additive `deadliner_persistence_focus_target_state_json(targetPayload, output, capacity)` accepts JSON `{"targetKind":"HABIT","targetUid":"uid"}` (or TASK) and returns the same typed `FocusTimerUiState`. `deadliner_persistence_focus_overview_json(output, capacity)` returns `FocusOverviewStatistics` using the Core repository's current local date/time zone.

The HarmonyOS command ABI accepts `start_habit_stopwatch`, `start_habit_countdown` (`habitUid`, optional countdown `durationSeconds`), `finish_and_record_habit` (`sessionUid`, optional `amount` and `expectedHabitUpdatedAt`), `resolve_habit_archive` / `resolve_habit_delete` (`habitUid`), and `select_session` (`sessionUid`). Optional `targetKind` / `targetUid` preserve selection after a completed operation; a late saved-session check-in never replaces a different live timer's selected target. Legacy Task operations and buffer ownership remain unchanged. Null/error output is not a successful mutation; clients must expose a recoverable error rather than inventing state.

### Focus time statistics

`FocusStatisticsRepository` is the platform-neutral, read-only boundary for finished focus sessions. `core.focusStatistics` exposes its local implementation. This is not a replacement for the existing Task/Habit statistics: it supplies focus-time projections to the existing Overview / Trends / Last month tabs.

```kotlin
val query = FocusStatisticsQuery(
    range = FocusStatisticsDateRange("2026-10-01", "2026-10-08"), // end is exclusive
    taskUid = null, // optional Task filter
    categoryUid = null, // optional current-category filter
    target = null, // optional typed Task/Habit UID filter
    targetKind = FocusTargetKind.HABIT, // optional filter for all Habit focus
)
val snapshot = core.focusStatistics.snapshot(query)
val tabs = core.focusStatistics.overview()
// Collect within the native screen lifecycle; upstream aggregation runs on Dispatchers.Default.
core.focusStatistics.observeOverview().collect { tabs -> /* update native UI */ }
```

Durations use actual effective seconds from non-deleted `FocusSession` records, excluding the active timer. A cross-midnight session is attributed once to its local start date. Task/Habit archival/deletion does not erase focus history. Titles use the current target when available, otherwise the latest session title snapshot. Categories use current Task/Habit/category metadata; missing/deleted targets fall into the unclassified bucket. Habit check-ins are not focus-time facts and never infer duration.

`snapshot` returns totals, task/category distributions, and a zero-filled daily series for a bounded range. A null range returns all-time totals/distributions without an unbounded daily series. `overview` returns today's, Monday-start current week's, current month's, and all-time summaries; 7 daily, 4 calendar-week, and 12 calendar-month points; plus the previous full calendar month's review and comparison with the month before it. The additive `rollingWeekly` field contains four consecutive seven-day windows ending today, for alignment with the existing Task trend charts; it does not change `weekly` or `thisWeek`. Percentage change is null when the comparison baseline is zero. Current periods exclude future local dates.

`FocusStatisticsSummary.durationByTarget` and `FocusDurationPoint.durationByTarget` expose `FocusDurationBreakdown(taskDurationSeconds, habitDurationSeconds)` in raw seconds, with the two components summing to the total. The fresh Core engine supplies a breakdown even for an empty period; null is reserved for a legacy projection that did not supply it. Do not guess type allocation from totals or habit completion counts. `tasks` / `taskCount` remain Task-only; `targets`, `habitCount` and `targetCount` are typed.

`recentDaysSummary`, `recentMonthsSummary`, and `recentWeeksSummary` are additive, nullable projections matching the exact ranges of `daily`, `monthly`, and `rollingWeekly`; use them for trend headers instead of rebuilding period aggregation on each platform. The last-month snapshot's summary supplies its type split and its total comparison still includes both target types.

`observeOverview` emits an initial snapshot and recalculates after session, Task, Habit, or category changes, including sync updates. The native client must resubscribe on resume and local date/time-zone changes; the Flow does not tick or poll the clock. This statistics adaptation introduces no schema or sync-payload change. The superseded generic `StatisticsRepository` draft was not published and has been removed.

### Habits

- `Habit(uid, name, description, color, iconKey, categoryUid, schedule, goal, tracking, status, sortOrder, reminder, createdAt, updatedAt, isDeleted)` is independent from `Task`.
- `HabitSchedule(kind, startOn, endOn, weekdaysMask, algorithmKey)` supports selected ISO weekdays, one-time plans, and versioned algorithms. `HabitWeekdays` defines the Monday-first bit mask; `EBBINGHAUS_CLASSIC_V1` is the retained classic review plan.
- `HabitGoal(window, targetCount)` defines scheduled-day, week, month, or lifetime aggregation. `HabitTracking(mode, unit, defaultIncrement)` defines check-off versus quantity input.
- `HabitRecord(uid, habitUid, occurredOn, count, status, createdAt, updatedAt, isDeleted)` stores check-ins.
- `HabitScheduleEngine`, `HabitProgressEngine`, and `HabitRecordCommandPolicy` are the cross-platform authority for due state, progress windows, and record mutations.
- `HabitPeriod` and `HabitGoalType` remain deprecated migration adapters only. Generated schedule rows were removed in schema v5; schedules are derived from the aggregate.

`HabitRepository` methods:

```kotlin
core.habits.list(): List<Habit>
core.habits.find(uid: String): Habit?
core.habits.records(habitUid: String): List<HabitRecord>
core.habits.allRecords(): List<HabitRecord>
core.habits.create(habit: Habit)
core.habits.update(habit: Habit)
core.habits.saveRecord(record: HabitRecord, operation: ChangeOperation = UPDATE)
core.habits.toggleRecord(habitUid, occurredOn, occurredAt): HabitActionResult
core.habits.applyPrimaryRecordAction(habitUid, occurredOn, occurredAt): HabitActionResult
core.habits.addRecordAmount(habitUid, occurredOn, amount, occurredAt): HabitActionResult
core.habits.setRecordAmount(habitUid, occurredOn, amount, occurredAt): HabitActionResult
core.habits.clearRecords(habitUid, occurredOn, occurredAt): HabitActionResult
core.habits.delete(uid: String, updatedAt: String)
```

`HabitPresentationEngine.statuses` produces the same date-specific due/progress/hint projection used by Habit List and Search. Each `HabitDayStatus` also exposes `canApplyPrimaryAction` and `primaryAction`: check-off habits use `CHECK_OFF`, while quantity habits use `ADD_QUANTITY` with `tracking.defaultIncrement`. `applyPrimaryRecordAction` advances an incomplete goal without overshooting its target; the next primary action after completion clears records in the active goal window and returns progress to zero. Platforms must keep the action enabled for completed, due, non-future dates and invoke this shared command instead of deciding increment/reset behavior themselves. `HabitReminderPlanner.plan` produces concrete local date-times; platform notification code must deliver those instances without recreating recurrence rules.

The AI editor proposal and LiFi `create_habit` contract use nested `schedule`, `goal`, `tracking`, and optional `reminder` objects. The executor accepts legacy `period/timesPerPeriod/goalType/totalTarget` input for one compatibility window and returns `compatibilityWarning` after conversion.

### Categories and supporting contracts

- `Category(uid, name, iconKey, colorHex, isPreset, sortOrder, createdAt, updatedAt, isDeleted)`; built-in values live in `CategoryPresets.all`.
- `CaptureItem`, `MemoryFragment` / `MemorySnapshot`, `UserProfile` / `UserTier`, and AI proposal models are stable common-domain contracts. Persistence repositories for Capture, Memory, and Profile are not exposed in this preview façade yet.

`CategoryRepository` methods:

```kotlin
core.categories.list(): List<Category>
core.categories.find(uid: String): Category?
core.categories.create(category: Category)
core.categories.update(category: Category)
core.categories.softDelete(uid: String, updatedAt: String)
```

## Change-log synchronization

`ChangeLogEntry` is the transport-safe immutable change record. `ChangeOperation` is `INSERT`, `UPDATE`, or `DELETE`.

`ChangeLogRepository` is exposed as `core.changeLog` for synchronization infrastructure:

```kotlin
core.changeLog.pending(): List<ChangeLogEntry>
core.changeLog.latestServerTimestamp(): Long?
core.changeLog.markSynced(changeIds, serverTimestamp)
```

To add a transport, implement `SyncTransport.push` and `SyncTransport.pull`; to write a winning remote record, implement `RemoteChangeApplier`. `SyncEngine.synchronize()` pushes local changes, marks acknowledgements, pulls remote changes, and returns `SyncReport`. LWW is provided by `LastWriteWinsConflictResolver`: client timestamp, device ID, then immutable change ID.

`RemoteChangeApplier` must call its `recordApplied` callback in the same database transaction as the remote business-table write.

## Snapshot API

`SnapshotV3Root` is the portable full-state JSON contract. It has typed `categories`, `tasks`, `habits`, `captures`, `memory_fragments`, and `user_profiles` collections. Each item carries `uid`, LWW `ver`, a `deleted` tombstone flag, and a live `doc`.

```kotlin
val json = SnapshotV3Codec.encode(snapshot) // validates before encoding
val snapshot = SnapshotV3Codec.decode(json) // validates after decoding
val issues = SnapshotV3Validator.validate(snapshot)
```

The iOS v2 migration API is side-effect free:

```kotlin
val result = SnapshotV2Migrator.migrate(
    SnapshotV2MigrationInput(tasks = taskV2, habits = habitV2, categories = categoryV2),
)
// Inspect result.issues before applying result.snapshot to local storage.
```

The v2 wire DTOs are in `com.deadliner.sync.snapshot.v2`; migration rules and explicit loss/ambiguity warnings are documented in `specs/kmp-persistence-sync/snapshot-v3.md`.

## iOS framework build

On macOS with JDK 21, link the Simulator Arm64 framework:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
  ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon --console=plain
```

The KMP framework base name is `Shared`. Link it into Xcode together with `sqlite3`; iOS-specific setup guidance is in `iosApp/README.md`.

## Preview compatibility

- The API and schema are preview contracts and may change before a stable release.
- Business deletion is tombstone-based. Do not replace repository deletes with physical SQL deletes.
- Network, authentication, retry scheduling, and snapshot-to-database application remain application-level adapters.

## 专注结束实况通知元数据

`DeadlinerDatabase.focusResultNotificationInfo(now)` 是只读的平台无关入口，返回最新有效已保存专注记录对应的 `NotificationMetadataEntry?`。手动结束、结束并完成任务、倒计时到时可生成结果；存在新计时、记录/任务已删除、结果过期或因任务失效自动终止时返回 null。

- `kind = FOCUS_RESULT`，`phase = FOCUS_FINISHED`，`actions = [OPEN_TASK]`。
- `ownerUid` 指向任务；`focusSessionUid` 指向专注记录；`focusDurationSeconds` 是已保存时长。
- `triggerAt` 是原始结束时间，`endsAt` 是结束后三分钟，编辑时长不重新计算期限。
- `version` 随记录/任务更新变化，平台执行动作前重新读取有效元数据；`OPEN_TASK` 只打开对应任务详情。
- 配置 `configureNotificationUpdates` 的元数据模式（提供 policyProvider）后，已提交的专注记录与计时变更会触发 `triggerNotificationInfoUpdate`，结果被合入 `realtime` 并提供 `nextRefreshAt` 到期边界。
- Android 已复用普通实时通知生命周期适配超级岛和原生 LiveUpdates；iOS ActivityKit、鸿蒙实况窗可按相同元数据接入，当前未新增其原生界面。
