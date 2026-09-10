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

`DeadlinerDatabase` exposes `categories`, `tasks`, `habits`, `focusSessions`, and `changeLog`. Business writes append a changelog record within the same SQL transaction. Focus sessions are available in the local Core API, but are not part of the current Snapshot v4 network/backup contract yet.

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

### Task focus timer (Core preview)

- `FocusTimerMode`: `STOPWATCH` or `COUNTDOWN` (1–180 minutes).
- `FocusTimerCommand`: `Start`, `Pause`, `Resume`, `Finish`, `FinishAndCompleteTask`, `Discard`, and `Reconcile`.
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

`FocusTimerViewModel` exposes the active timer, today duration/session count, selected Task today/all-time duration, recent sessions, and session adjustment/deletion. Call `tick()` once per visible second; it recalculates display state from in-memory anchors and does not persist per-second updates. Platform bridge helpers and Snapshot/Server/Backup support are pending the next contract batch.

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
