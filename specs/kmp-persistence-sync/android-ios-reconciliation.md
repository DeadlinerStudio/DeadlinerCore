# Android 与 iOS 领域模型对齐（最终 DDL）

## 结论

Android 是功能语义的基线；iOS 模型提供两个应保留的演进：`Category` 分类，以及 Task 与 Habit 的独立聚合。最终模型不再保留 Android 的 `DDLItem.type`、`Habit.ddlId`、`habit_count`、`habit_total_count`，也不再将 `sub_tasks_json` 当作持久化格式。

所有同步实体使用跨端稳定 `uid`，并保存 `createdAt`、`updatedAt` 与 `isDeleted`。删除为墓碑更新；物理清理只能由后续同步保留期任务执行，不能由业务仓储直接执行。

## 来源与取舍

| 能力 | Android 原始模型 | iOS 提取模型 | 最终模型 |
| --- | --- | --- | --- |
| 任务主体 | `DDLItem`，包含任务和习惯的混合字段 | 独立 `Task` | 独立 `Task`，保留标题、备注、起止/截止时间、星标、日历事件与状态机 |
| 任务状态 | `DDLState` + 单向状态机 | `TaskState`，错误地把重复完成/放弃改为切换 | 采用 Android 状态机：重复操作为 no-op，不反向切换 |
| 任务状态展示 | `DDLStatus` | 未显式建模 | 继续由当前时间、截止时间和 TaskState 派生，绝不入库 |
| 子任务 | `sub_tasks_json` | `TaskSubtask` 独立对象 | 独立 `task_subtask` 表，保留排序和墓碑 |
| 习惯主体 | `Habit` 仍以 `ddlId` 一对一挂在 DDL | 独立 `Habit` | 独立 `Habit`，无 Task 外键、link 表或镜像计数字段 |
| 习惯目标 | 周期/总目标、次数、提醒 | 增加 ONCE、Ebbinghaus、提醒对象 | 保留全部；周期进度从记录与规则派生 |
| 打卡 | 可同日追加多条记录 | `HabitRecord` 有 UID | 事件记录保留 UID、日期、数量、状态及同步墓碑 |
| 艾宾浩斯 | 从创建日及固定间隔即时推导 | 引入 schedule item | 默认仍由规则推导；`habit_schedule_item` 仅保存可同步的例外/显式状态，不保存周期进度缓存 |
| 分类 | 无 | Category 与预设 | Category 可被 Task、Habit 引用；删除分类时引用置空 |
| 收集箱/记忆/档案 | Android 有 nickname/avatar | iOS 有 Capture、Memory、tier | 保留 Capture、Memory；统一 UserProfile（昵称、头像、tier），不用 JSON content 列 |
| 删除与同步 | DDL 有 tombstone，习惯及记录硬删 | 仅 Category/Subtask 有墓碑 | 每个可同步实体均有墓碑，changelog 记录同一事务中的变更 |

## 最终实体

### Task

`Task(uid, title, note, startAt?, dueAt?, state, completedAt?, categoryUid?, isStarred, calendarEventId?, createdAt, updatedAt, isDeleted, subtasks)`。

- `state` 仅可为 `ACTIVE`、`COMPLETED`、`ARCHIVED`、`ABANDONED`、`ABANDONED_ARCHIVED`。
- `isCompleted`、`isArchived` 和 `DDLStatus` 是兼容旧库的派生概念，不写入新库。
- Task 删除只更新其自身墓碑；子任务各自写墓碑，以便远端副本能收敛。

### Habit

`Habit(uid, name, description?, color?, iconKey?, categoryUid?, period, timesPerPeriod, goalType, totalTarget?, status, sortOrder, reminder?, createdAt, updatedAt, isDeleted)`。

- 不含任何 task/DDL 外键。
- `HabitRecord(uid, habitUid, occurredOn, count, status, createdAt, updatedAt, isDeleted)` 是打卡事实；同日多条记录合法，周期进度由查询聚合。
- `HabitScheduleItem(uid, habitUid, dueAt, state, createdAt, updatedAt, isDeleted)` 是显式排程/例外状态。日、周、月和艾宾浩斯默认排程仍可由规则计算。

### 其他同步实体

- `Category`：现有分类字段及墓碑。分类处于墓碑状态时，引用它的 Task/Habit 在产品层视为“未分类”；保留 UID 以支持远端收敛和可能的恢复。
- `CaptureItem`：文本、来源、已消费标志和墓碑。
- `MemoryFragment`：内容、类别、重要度、更新时间和墓碑；`MemorySnapshot` 是内存投影，非表，并以结构化 `UserProfile` 取代 iOS 提取阶段的字符串档案。
- `UserProfile`：单例逻辑档案的稳定 UID、昵称、头像文件名、订阅层级、创建/更新时间和墓碑。

## 最终 SQL 表规则

1. 每个业务表的主键是 `TEXT uid`，不暴露自增主键为同步标识。
2. 所有时间使用 UTC ISO-8601 文本；`occurred_on` 是用户当地日期（`YYYY-MM-DD`）。
3. `task.category_uid` 与 `habit.category_uid` 为可空外键，`ON DELETE SET NULL`；仓储不物理删除 Category。墓碑不会触发 SQL 外键动作，因而引用 UID 保留到同步保留期结束。
4. 子任务、记录和显式排程均不因为父实体的业务删除而级联物理删除；各自的墓碑由同一事务写入。
5. 列表查询默认过滤 `is_deleted = 0`，按 UID 查询保留墓碑读取能力，供同步合并使用。
6. `changelog` 保持 append-only；每一次本地实体变更和对应墓碑与日志在同一 SQLite 事务中提交。

## 非持久化/派生数据

- `DeadlineType`：由聚合类型决定，不存储。
- `habitCount`、`habitTotalCount`：由 `HabitRecord` 聚合得到，不存储。
- `DDLStatus`、周期完成进度、艾宾浩斯下一次复习提示：由实体、记录、时钟和排程规则计算，不存储。
- Android `HabitMetaData` JSON：只用于旧库导入，不进入新 Schema。
