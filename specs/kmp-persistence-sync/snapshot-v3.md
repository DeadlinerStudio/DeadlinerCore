# Snapshot v3 契约与 v2 迁移

## v3 文件形状

`SnapshotV3Root` 是单文件全量快照，固定 `format_version = 3`。根版本 `version.ts` / `version.dev` 用于生成标记；冲突判定使用每个 item 的 `ver.ts` / `ver.ctr` / `ver.dev`，与 iOS v2 的 LWW 比较顺序完全一致。

v3 将原来的三份文件统一为下列 typed collections：

- `categories`：`Category` 或无 `doc` 的分类墓碑。
- `tasks`：`Task`（包含 `TaskSubtask`）或无 `doc` 的任务墓碑。
- `habits`：`Habit`、`HabitRecord`、`HabitScheduleItem` 聚合，或无 `doc` 的习惯墓碑。
- `captures`、`memory_fragments`、`user_profiles`：为最终领域模型预留的同样模式；v2 没有对应输入，迁移时为空。

所有 live item 必须有 `doc`，所有墓碑 `deleted = true` 必须没有 `doc`；文档 UID 必须等于 item UID，嵌套的子任务/记录/排程必须引用其父 UID。`SnapshotV3Codec` 在编码及解码时执行这些检查。

## iOS v2 → v3 映射

| iOS 输入 | v3 目标 | 规则 |
| --- | --- | --- |
| `snapshot-v2.json` 中 `type = task` | `tasks` | `name → title`，空时间字符串转 `null`，`state` 直接映射，`sub_tasks` 变为 `TaskSubtask`。 |
| `snapshot-v2.json` 中 `type = habit` | `habits` | 有 `habit-snapshot-v2.json` 时跳过旧承载项；只有该文件时读取旧 `note` 中的 `HabitMetaData`，恢复频率和 `completedDates`。 |
| `habit-snapshot-v2.json` | `habits` | 独立 Habit 文件是权威来源；record 的 v2 没有 UID，迁移以 `habitUid + 原数组序号 + date` 生成稳定 UID。 |
| `category-snapshot-v2.json` | `categories` | 字段一一映射，`is_preset != 0` 映射为 `true`。 |
| 任意 v2 `deleted = true` | 对应 v3 item 墓碑 | 不创建 `doc`，保留 UID 与 `ver`。 |

任务 v2 墓碑没有记录原来的 `type`。若没有同 UID 的独立 Habit v2 项，迁移器只能将其投影为任务墓碑，并产生 `AMBIGUOUS_TOMBSTONE_ASSUMED_TASK` 告警。调用方必须在写入本地数据库前检查 `SnapshotV2MigrationResult.issues`。

## 显式降级

- 未提供独立 Habit v2 文件时，旧习惯承载项会产生 `LEGACY_HABIT_CARRIER_FALLBACK`；不能解析的旧 `note` 不会伪装成精确配置，而是采用每日/一次的保守默认值并产生告警。
- 旧 `HabitMetaData.total` 仅在 `frequencyType = TOTAL` 时映射为 `totalTarget`；周期习惯不把历史上限误写为 v3 总目标。
- 不认识的状态、周期或目标类型采用安全默认值并产生 issue；live item 缺少 `doc` 则被跳过并产生 issue。

## 使用边界

迁移器和 codec 是 `commonMain` 的纯函数，不访问网络或 SQLDelight。它们产出的 v3 文档经校验后，后续由同步导入编排层在事务中应用到业务仓储。
