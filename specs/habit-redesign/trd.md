# Habit 重构 TRD

## 文档信息

- 产品：Deadliner
- 功能：Habit 习惯系统重构
- 版本：TRD v0.1
- 状态：待技术评审
- 日期：2026-08-18
- 依赖：`prd.md`、`spec.md`、`module-plan.md`
- 核心仓库：`~/Codes/ProjectDeadliner/DeadlinerCore`
- Android：`~/Codes/ProjectDeadliner/Deadliner`
- iOS：`~/Codes/iOS/Deadliner`
- HarmonyOS：`~/Codes/HarmonyOS/Deadliner`

## 1. 现状摘要

- KMP `Habit` 已与 `Task` 分表、分模型、分仓储；只共享可选 `category_uid`。
- 当前 `HabitPeriod` 混合重复规则、目标窗口、一次性计划和算法计划。
- `HabitListViewModel` 内联了周期边界、进度与经典艾宾浩斯算法。
- `habit_schedule_item` 有存储和 Snapshot 合同，但没有生产业务数据的调用路径。
- Reminder 只保存时间；shared 编排未传递周几，平台调度各自解释。
- iOS、HarmonyOS 和部分扩展端重复实现艾宾浩斯与周期计算；Android 主列表已主要消费 KMP state，但 Receiver 和 Widget 仍是独立入口。

## 2. 技术目标

1. 在 commonMain 建立唯一 Habit 日期、进度、命令与提醒规划权威。
2. 用独立值对象替换 `period/timesPerPeriod/goalType/totalTarget` 组合。
3. 新数据库 schema 不保留旧字段双写。
4. Snapshot 升级协议版本，旧 V3/V2 仅作为迁移输入。
5. 三端只负责表单映射、状态展示和系统通知投递。
6. 所有写入口，包括 Widget、搜索和 AI tool，都经过同一领域命令校验。

## 3. 目标架构

```text
Platform UI / Widget / Watch / AI
                |
                v
Habit ViewModels / Use Cases
                |
      +---------+----------+
      |         |          |
ScheduleEngine ProgressEngine CommandPolicy
      |         |          |
      +---------+----------+
                |
        HabitRepository
          /           \
 SQLDelight + Sync   ReminderPlanner
                         |
                Platform Notification Gateway
```

约束：平台 UI 不允许根据枚举自行计算计划日、周期边界或艾宾浩斯偏移。

## 4. 领域合同

### 4.1 Habit

建议 commonMain 合同：

```kotlin
@Serializable
data class Habit(
    val uid: String,
    val name: String,
    val description: String? = null,
    val color: Int? = null,
    val iconKey: String? = null,
    val categoryUid: String? = null,
    val schedule: HabitSchedule,
    val goal: HabitGoal,
    val tracking: HabitTracking = HabitTracking.checkOff(),
    val status: HabitStatus = HabitStatus.ACTIVE,
    val sortOrder: Int = 0,
    val reminder: HabitReminder? = null,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val isDeleted: Boolean = false,
)
```

### 4.2 Schedule

```kotlin
@Serializable
data class HabitSchedule(
    val kind: HabitScheduleKind,
    val startOn: String,
    val endOn: String? = null,
    val weekdaysMask: Int? = null,
    val algorithmKey: HabitAlgorithmKey? = null,
)

enum class HabitScheduleKind { WEEKLY_DAYS, ONCE, ALGORITHM }
enum class HabitAlgorithmKey { EBBINGHAUS_CLASSIC_V1 }
```

校验：

- `startOn/endOn` 必须是 ISO `YYYY-MM-DD`，且 `endOn >= startOn`。
- `WEEKLY_DAYS` 必须有 `weekdaysMask in 1..127`，不得有 `algorithmKey`。
- `ONCE` 不得有 weekday 或 algorithm。
- `ALGORITHM` 必须有 `algorithmKey`，不得有 weekday。
- bit 0 为 ISO Monday，bit 6 为 Sunday。

### 4.3 Goal 与 Tracking

```kotlin
@Serializable
data class HabitGoal(
    val window: HabitGoalWindow,
    val targetCount: Int,
)

enum class HabitGoalWindow { SCHEDULED_DAY, WEEK, MONTH, LIFETIME }

@Serializable
data class HabitTracking(
    val mode: HabitTrackingMode,
    val unit: String? = null,
    val defaultIncrement: Int = 1,
)

enum class HabitTrackingMode { CHECK_OFF, QUANTITY }
```

校验：

- `targetCount > 0`，`defaultIncrement > 0`。
- `CHECK_OFF` 强制 `targetCount=1`、`defaultIncrement=1`、`unit=null`。
- `QUANTITY` 的 `unit` 去首尾空格后最多 12 个 Unicode 字符；空串归一化为 null。
- 艾宾浩斯强制 `SCHEDULED_DAY + targetCount=1 + CHECK_OFF`。

### 4.4 Reminder

```kotlin
@Serializable
data class HabitReminder(
    val localTime: String,
    val isEnabled: Boolean = true,
)
```

- `localTime` 严格为 `HH:mm`。
- Reminder 不存 weekdays；日期由 ScheduleEngine 计算。

### 4.5 Record

保留现有 `HabitRecord` 结构，`count` 表示一次增量或设置后的持久化结果。首选事件式增量记录：每次 `ADD_PROGRESS` 新建一条正数记录；`SET_PROGRESS/CLEAR` 在事务内软删当天旧记录并写入新记录或留空。

`SKIPPED/FAILED` 首版保留读取与同步兼容，但不进入完成进度；新 UI 暂不创建这两类记录。

## 5. 领域引擎

### 5.1 HabitScheduleEngine

```kotlin
interface HabitScheduleEngine {
    fun state(habit: Habit, date: LocalDate): HabitDueState
    fun nextDueDate(habit: Habit, after: LocalDate, inclusive: Boolean = false): LocalDate?
    fun dueDates(habit: Habit, range: ClosedRange<LocalDate>): List<LocalDate>
}
```

`HabitDueState`：

- `DUE`
- `NOT_STARTED(daysUntil)`
- `NOT_SCHEDULED(nextDueOn)`
- `COMPLETED_SCHEDULE`
- `ENDED`

算法：

- `WEEKLY_DAYS`：日期在有效区间且 weekday bit 命中即 DUE。
- `ONCE`：仅 `startOn` DUE；之后 COMPLETED_SCHEDULE。
- `EBBINGHAUS_CLASSIC_V1`：`startOn + [0,1,2,4,7,15,30,60]` DUE。

### 5.2 HabitProgressEngine

```kotlin
interface HabitProgressEngine {
    fun progress(habit: Habit, records: List<HabitRecord>, date: LocalDate): HabitProgress
    fun dayOverview(habits: List<Habit>, records: List<HabitRecord>, date: LocalDate): HabitDayOverview
}
```

窗口：

- `SCHEDULED_DAY`：`date..date`。
- `WEEK`：ISO Monday..Sunday。
- `MONTH`：自然月首日..末日。
- `LIFETIME`：`startOn..date`。

只统计 `COMPLETED && !isDeleted`，按 `count` 求和。`HabitProgress.isCompleted = doneCount >= targetCount`，展示比率 clamp 到 1，真实 doneCount 不截断。

每日概览只纳入：

- `goal.window == SCHEDULED_DAY`
- 当日 ScheduleEngine 为 DUE
- Habit ACTIVE 且未删除

### 5.3 HabitCommandPolicy

禁止 UI 直接调用含糊的 `toggleRecord`。新命令：

```kotlin
sealed interface HabitRecordCommand {
    data object Complete : HabitRecordCommand
    data object UndoCompletion : HabitRecordCommand
    data class AddProgress(val amount: Int) : HabitRecordCommand
    data class SetProgress(val amount: Int) : HabitRecordCommand
    data object ClearProgress : HabitRecordCommand
}
```

返回结构：

```kotlin
data class HabitCommandResult(
    val outcome: HabitCommandOutcome,
    val habit: Habit?,
    val progress: HabitProgress?,
)

enum class HabitCommandOutcome {
    APPLIED, NO_OP, NOT_FOUND, NOT_DUE, FUTURE_DATE, INACTIVE, INVALID_COMMAND
}
```

所有平台、Widget 和 AI 调用同一 repository/use case 入口。旧 `toggleRecord` 在一个兼容周期内标记 deprecated，并立即转换成明确命令；三端切换完成后删除。

## 6. 数据库设计

### 6.1 新 habit 表

目标列：

```sql
CREATE TABLE habit (
    uid TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT,
    color INTEGER,
    icon_key TEXT,
    category_uid TEXT REFERENCES category(uid) ON DELETE SET NULL,
    schedule_kind TEXT NOT NULL,
    schedule_start_on TEXT NOT NULL,
    schedule_end_on TEXT,
    schedule_weekdays_mask INTEGER,
    schedule_algorithm_key TEXT,
    goal_window TEXT NOT NULL,
    goal_target_count INTEGER NOT NULL,
    tracking_mode TEXT NOT NULL,
    tracking_unit TEXT,
    tracking_default_increment INTEGER NOT NULL,
    status TEXT NOT NULL,
    sort_order INTEGER NOT NULL,
    reminder_local_time TEXT,
    reminder_enabled INTEGER,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    is_deleted INTEGER NOT NULL DEFAULT 0
);
```

最终 schema 删除旧 `period/times_per_period/goal_type/total_target`，不双写。

建议约束由 Kotlin domain validation + migration tests 保障；SQL 可增加 `CHECK`，前提是 SQLDelight 各目标一致支持。

### 6.2 habit_record

- 结构保持不变。
- 保留 `(habit_uid, is_deleted, occurred_on)` 索引。
- 不加 `(habit_uid, occurred_on)` 唯一约束，因为数量模式允许多条增量记录。

### 6.3 habit_schedule_item

- 从最终 AppDatabase schema、repository、changelog table kind 和新 Snapshot 中移除。
- 原因：计划可由 Schedule 值对象确定性生成，record 已表达完成状态，旧表没有业务生产者。
- migration 执行前先统计本地行数并写诊断日志；发现非空不阻塞升级，但记录数量。旧行不参与新行为，备份迁移报告保留该事实。

## 7. SQLDelight 迁移

当前 migration 到 `3.sqm`，下一次使用 `4.sqm`。推荐步骤：

1. 创建新结构临时表 `habit_v2`。
2. 逐行读取旧 Habit，由 Kotlin migration coordinator 计算新值，或在 SQL 可确定部分先复制、再由启动迁移补齐。
3. 校验新表行数与 UID 集合等于旧表。
4. 在明确处理外键后替换旧表并重建索引。
5. 删除 `habit_schedule_item`。
6. 写入一次性 migration marker 与异常计数。

由于 `createdAt -> startOn` 涉及时区，不能只用纯 SQL：

- 首次升级使用设备当前时区把 `createdAt` 转为本地日期。
- 结果立即持久化为 `startOn` 并参与同步，此后不再根据时区重算。
- 无法解析时退化为 `createdAt.substringBefore('T')`；仍失败则使用升级当天并记录诊断。

旧字段映射：

| 旧值 | schedule | goal | tracking |
| --- | --- | --- | --- |
| DAILY/PER_PERIOD | 全 7 天 | SCHEDULED_DAY/timesPerPeriod | target=1 时 CHECK_OFF，否则 QUANTITY |
| WEEKLY/PER_PERIOD | 全 7 天 | WEEK/timesPerPeriod | target=1 时 CHECK_OFF，否则 QUANTITY |
| MONTHLY/PER_PERIOD | 全 7 天 | MONTH/timesPerPeriod | target=1 时 CHECK_OFF，否则 QUANTITY |
| 任意/TOTAL | 按 period 转 schedule | LIFETIME/totalTarget | 缺失时 target=`max(已完成总量, 1)` 并记录异常；target=1 时 CHECK_OFF，否则 QUANTITY |
| EBBINGHAUS | CLASSIC_V1 | SCHEDULED_DAY/1 | CHECK_OFF |
| ONCE | ONCE(startOn) | SCHEDULED_DAY/1 | CHECK_OFF |

旧数据没有 unit，迁移为 null；`defaultIncrement=1`。

## 8. Snapshot、备份与同步

### 8.1 SnapshotV4

- 新增 `SnapshotV4Root` 和 `SnapshotV4HabitDocument`。
- Habit 文档包含 `habit + records`，不再包含 `scheduleItems`。
- `SnapshotV3` 保持只读 decode/migrate，不再 encode 新数据。
- V2 Task carrier 直接投影为新 Habit，不产生 Task 关联。

### 8.2 协议兼容

- 新客户端第一次读取 V3 后迁移并写 V4。
- 已写入 V4 的远端数据集，旧客户端不得继续写 V3 覆盖。同步 transport 需要先读取 manifest/protocol version，不支持时返回 `UPGRADE_REQUIRED`。
- changelog 表名继续使用 `habit` 与 `habit_record`；移除 `habit_schedule_item`。
- schedule/goal/tracking 作为 Habit 聚合字段进行原子 LWW，不做字段级合并。

### 8.3 备份

- `BackupService` 新导出格式携带 schema/snapshot version。
- 恢复 V3/V2 时走同一迁移器。
- 导入报告包含：迁移 Habit 数、默认值数、解析失败数、丢弃旧 schedule item 数。

## 9. Reminder 架构

### 9.1 Shared plan

```kotlin
@Serializable
data class HabitReminderInstance(
    val identifier: String,
    val habitUid: String,
    val title: String,
    val fireAt: String,
    val localDate: String,
)
```

identifier：`habit:<habitUid>:<YYYY-MM-DD>:<HH-mm>`。

Planner：

```kotlin
fun plan(
    habits: List<Habit>,
    records: List<HabitRecord>,
    now: Instant,
    timeZone: TimeZone,
    horizonDays: Int,
    limit: Int,
): List<HabitReminderInstance>
```

步骤：

1. 过滤 active、未删除、reminder enabled。
2. 用 ScheduleEngine 生成窗口内 due dates。
3. 用 ProgressEngine 排除已完成目标；周期目标完成后排除窗口剩余日期。
4. 组合本地日期与时间，转换为当前时区 Instant。
5. 排除过去时间，按 fireAt、habit sortOrder、UID 稳定排序，截断到 limit。

### 9.2 Gateway

```kotlin
interface NotificationGateway {
    fun replaceHabitNotifications(instances: List<HabitReminderInstance>)
    fun cancelHabitNotifications(habitUid: String)
}
```

平台不再接收 `daysOfWeek` 并自行推导规则。允许平台提供 capability：`habitReminderLimit` 与建议 horizon。

刷新触发：

- App 初始化/回前台。
- Habit 或 Record 成功写入。
- 同步/恢复应用新数据。
- Android boot/package replaced/date/time/timezone changed。
- iOS background refresh 与 significant time change。
- HarmonyOS 对应系统事件。

### 9.3 平台要点

- Android：`AndroidNotificationGateway` replace 全量实例；Receiver 只展示并请求刷新，不再无条件按每日续期。BootReceiver 重新规划。PendingIntent request code/identifier 必须稳定。
- iOS：用具体 `UNCalendarNotificationTrigger` 单次实例，统一遵守系统待处理数量上限；取消旧 `HABIT_*` 与新前缀后重建。
- HarmonyOS：从 shared 实例发布单次 Reminder；不再固定 `[1,2,3,4,5,6,7]` 循环。

## 10. ViewModel 合同

### 10.1 Editor

`HabitFormData` 替换旧 period 字段：

```kotlin
data class HabitFormData(
    val name: String = "",
    val scheduleKind: HabitScheduleKind = WEEKLY_DAYS,
    val startOn: String,
    val weekdaysMask: Int = 127,
    val goalWindow: HabitGoalWindow = SCHEDULED_DAY,
    val trackingMode: HabitTrackingMode = CHECK_OFF,
    val targetCount: Int = 1,
    val unit: String? = null,
    val defaultIncrement: Int = 1,
    val reminder: HabitReminder? = null,
    // existing presentation fields omitted
)
```

- validation 返回结构化 field errors，平台不匹配中文错误文本。
- Ebbinghaus 切换时 ViewModel 负责归一化固定字段。
- Android `AddDDLActivity` 可继续承载现有页面，只做 FormData/intent 接线；本 TRD 不要求重构该 Activity。

### 10.2 List

`HabitListUiState` 至少拆分：

- `dailyHabits: List<HabitDayStatus>`
- `periodGoals: List<HabitPeriodProgress>`
- `upcomingReviews: List<HabitUpcomingStatus>`
- `dayOverview`
- `selectedDate`

`HabitDayStatus` 直接携带 `dueState`、`progress`、`availableCommands`，平台不再根据 period 猜测按钮是否可用。

## 11. AI/LiFi 合同

新的 `create_habit` 参数：

```json
{
  "name": "背单词",
  "schedule": {
    "kind": "WEEKLY_DAYS",
    "weekdaysMask": 31,
    "startOn": "2026-08-18"
  },
  "goal": {
    "window": "SCHEDULED_DAY",
    "targetCount": 20
  },
  "tracking": {
    "mode": "QUANTITY",
    "unit": "个单词",
    "defaultIncrement": 1
  },
  "reminder": {
    "localTime": "08:30",
    "isEnabled": true
  }
}
```

- Tool schema 对各条件使用 enum、minimum 和 required。
- 旧 `period/timesPerPeriod/goalType/totalTarget` tool call 由 normalizer 转换一个版本，并在结果中返回 compatibility warning。
- 用户确认后的执行仍走 HabitEditor/use case，不由 tool executor 直接拼 SQL。

## 12. 三端文件映射

### KMP

- `domain/habit/Habit*.kt`：合同拆分。
- `domain/habit/HabitScheduleEngine.kt`
- `domain/habit/HabitProgressEngine.kt`
- `domain/habit/EbbinghausClassicPlan.kt`
- `db/repository/HabitRepository.kt`
- `viewmodel/habit/HabitEditorViewModel.kt`
- `viewmodel/habit/HabitListViewModel.kt`
- `service/HabitReminderPlanner.kt`
- `service/NotificationOrchestrator.kt`
- `sync/snapshot/v4/*`

### Android

- `platform/kmp/KmpV6Runtime.kt`
- `AddDDLActivity.kt` 与 `ui/editor/DeadlineEditorComponents.kt`：最小表单接线。
- `ui/main/simplified/HabitDisplayLayout.kt`、`HabitComponents.kt`
- `ui/main/shared/MainSearchResultsContent.kt`、批量操作入口。
- `notification/AndroidNotificationGateway.kt`、`KmpNotificationReceiver.kt`、`BootReceiver.kt`
- `widgets/HabitMiniWidget.kt`、`HabitMediumWidget.kt` 与配置页。

`AddDDLActivity.kt` 的既有大文件状态是本次已批准例外：不把拆分作为 Habit 上线前置条件，但禁止把计划、进度或提醒算法写入其中。

### iOS

- `Core/Application/Services/KMPHabitReminderScheduler.swift`
- `Data/Persistence/Habit/KMPHabitStore.swift`
- `Features/Home/Persistence/KMPHabitPresentationStore.swift`
- `Shared/UI/Components/HabitEditorSheetView.swift`
- Home/Search/Category/Archive/Widget/Watch 删除本地规则并消费 shared state。

### HarmonyOS

- `common/kmp/KmpHabitRepositoryAdapter.ets`
- `common/repository/HabitRepository.ets`
- `pages/routes/main/modify/habit/*`
- `pages/routes/main/HabitListSection.ets`
- `utils/SearchUtils.ets`
- `utils/notification/ReminderScheduler.ets`

## 13. 测试策略

### 13.1 commonTest

- 127 种非空 weekdays mask 的一周 due 判定。
- start/end 边界、跨年、闰日、ISO 周一边界、自然月边界。
- 四种 goal window 聚合与超额进度。
- 非计划日、未来日、归档状态的命令拒绝。
- 艾宾浩斯每个 due/non-due offset、开始前、60 天后。
- reminder planner 排序、完成过滤、时区/DST、limit。

### 13.2 数据与同步

- SQLDelight 旧 schema fixture 到新 schema 的 UID/记录守恒。
- DAILY/WEEKLY/MONTHLY/TOTAL/ONCE/EBBINGHAUS 映射。
- SnapshotV4 round-trip、V3/V2 migration、版本拒绝。
- WebDAV 多设备 LWW 与 tombstone。
- 旧 schedule item 非空时的诊断与可恢复性。

### 13.3 平台

- Android Alarm 创建/替换/取消、Receiver/BootReceiver、Widget 非计划日拒绝。
- iOS pending notification identifier、cap 截断、Widget/Watch due state。
- HarmonyOS Reminder replace/cancel 与 weekday/艾宾浩斯样本。
- 三端使用一组 golden JSON，对同一日期输出相同 due/progress/hint。

## 14. 迁移与发布顺序

1. 冻结 PRD/TRD 与 golden fixtures。
2. 创建 breaking change 前的全工作区保护提交。
3. 实现纯领域合同/引擎及 commonTest。
4. 实现数据库与 SnapshotV4 迁移。
5. 改 repository、ViewModel、AI 和 reminder planner。
6. Android、iOS、HarmonyOS 接入同一 golden fixtures。
7. 完成一批大型更改后统一编译和端到端验证。
8. 灰度迁移真实样本，回写文档。

## 15. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 多端存在旧算法导致双权威 | shared 状态接入时同步删除平台计算函数，golden fixture 对比 |
| 时区导致旧 Ebbinghaus 锚点变化 | 首次迁移固化 startOn，之后同步明确日期 |
| iOS 通知上限 | planner 全局排序 + capability limit + 滚动刷新 |
| 旧客户端覆盖新 Snapshot | 远端 protocol version gate，返回 UPGRADE_REQUIRED |
| Widget 绕过计划日写入 | 所有命令在 common domain 二次校验 |
| Android 现有脏工作区 | 迁出分支前确认边界，不格式化或提交无关 Task/Glance 文件 |
| 数量记录撤销语义混乱 | 使用显式 command，废弃 toggleRecord |

## 16. 完成定义

- PRD 验收标准全部有测试或人工证据。
- KMP 是计划、进度、艾宾浩斯与 reminder plan 的唯一权威。
- 新 schema 不再包含旧 HabitPeriod 组合字段和 `habit_schedule_item`。
- SnapshotV4、V3/V2 migration 和旧数据库迁移均通过。
- 三端、搜索、Widget/Watch 和 AI 创建入口行为一致。
- 大文件扫描、规格校验、统一编译与文档回写完成。
