# Habit Scheduling Redesign

## 背景

- 创建日期：2026-08-18
- 功能标识：`habit-redesign`
- 基线分支：`refactor/kmp`
- 工作分支：`codex/habit-redesign`
- 需求来源：KMP 迁移后重新定义习惯的产品行为，支持按周几执行与提醒，并完整保留艾宾浩斯能力。

### 当前事实

1. KMP 中 `Habit` 已经是独立聚合：`habit`、`habit_record`、`habit_schedule_item` 都只以 `habit_uid` 关联，运行时不依赖 `Task`；Habit 与 Task 只可能共享同一个 `category_uid`。
2. `SnapshotV2Migrator` 仍能从旧版 Task carrier 恢复 Habit。这只是历史数据兼容入口，不是当前领域依赖。
3. 当前 `HabitPeriod` 同时表达目标统计周期（每日/每周/每月）、一次性行为和艾宾浩斯算法，重复日历与目标窗口没有解耦。
4. 当前 `WEEKLY` 表示“本周累计完成 N 次”，不是“每周指定星期几执行”。普通 Habit 在每天都可见、可打卡，也每天进入完成率分母。
5. 当前艾宾浩斯以 `createdAt` 的本地日期为锚点，固定使用 `[0, 1, 2, 4, 7, 15, 30, 60]` 天曲线；非复习日不可打卡，也不进入当日完成率分母。
6. `habit_schedule_item` 已有持久化与同步能力，但没有生成器或业务消费者；目前它不是艾宾浩斯或普通重复规则的事实来源。
7. Reminder 目前只有一个本地时间。公共通知配置虽然已有 `daysOfWeek`，但 shared 编排没有传递它，三端实际调度行为也不一致：iOS 原生层规划未来 7 天、HarmonyOS 固定每天循环；Android 设置下一次单次 Alarm，并由 `KmpNotificationReceiver` 在投递后继续安排下一次，但续期仍按“每日”处理，不理解计划日或艾宾浩斯 due 状态。
8. 艾宾浩斯判断目前在 shared、iOS、HarmonyOS、搜索和部分设备扩展中重复实现，存在跨端漂移风险。
9. AI 提取、LiFi `create_habit` 工具、同步快照和三端编辑器都直接依赖旧的 `period/timesPerPeriod/goalType/totalTarget` 合同，改造必须覆盖这些入口。
10. Android 权威仓库为 `~/Codes/ProjectDeadliner/Deadliner`。它已经用 KMP ViewModel 驱动 Habit 创建和列表，并且 Habit 行为还被搜索、批量打卡、Mini/Medium Widget、通知 Receiver、开机恢复共同消费。

## 目标

- 保持 Habit 与 Task 的领域、表结构、仓储、同步和通知标识完全独立。
- 支持按 ISO 周几重复：每天、工作日、周末和自定义星期组合。
- 让普通习惯只在计划日可打卡并进入当日完成率；提醒默认严格跟随同一计划日，避免“周末不执行但仍提醒”。
- 将“什么时候需要做”“一个统计窗口完成多少”“如何提醒”拆成独立概念。
- 将日历判定、进度聚合、艾宾浩斯计划和提醒实例规划统一下沉到 shared，三端只负责展示和平台通知投递。
- 保留经典艾宾浩斯曲线、非复习日阻断、下一次复习提示、周期结束提示和仅在复习日提醒的行为。
- 为现有 DAILY/WEEKLY/MONTHLY/ONCE/EBBINGHAUS 数据提供确定、可测试、可同步的迁移规则。
- 更新 AI 创建合同，使“工作日背单词”等自然语言能够落到周几计划，而不是被降级为 DAILY。

## 非目标

- 本轮不改造 Task/DDL 的重复任务能力。
- 本轮不实现任意 cron、隔 N 天、节假日排除、地理位置提醒或一天多个提醒时间。
- 本轮不引入自适应记忆算法、答题质量评分或 SM-2；艾宾浩斯先保留经典固定曲线。
- 本轮不重做成就、连续天数、社交或付费体系。
- 不建设跨功能的通用单位系统；Habit 仅支持可选的短单位文本与整数数量追踪。

## 建议领域合同

### 1. 重复计划

`HabitSchedule` 只回答某个本地日期是否为计划日：

- `kind`：`WEEKLY_DAYS`、`ONCE`、`EBBINGHAUS`。
- `startOn`：明确的本地日期，不再借用 `createdAt` 表示业务起点。
- `endOn`：可选；为空表示持续有效。
- `weekdaysMask`：仅 `WEEKLY_DAYS` 使用，ISO 周一到周日的 7 bit 非空掩码。
- `algorithmKey`：仅算法计划使用，首版固定为 `EBBINGHAUS_CLASSIC_V1`。

预设只是 UI 快捷方式，不新增领域枚举：

- 每天：周一至周日。
- 工作日：周一至周五。
- 周末：周六、周日。
- 自定义：任意非空组合。

### 2. 目标

`HabitGoal` 只回答在哪个窗口累计多少：

- `window`：`SCHEDULED_DAY`、`WEEK`、`MONTH`、`LIFETIME`。
- `targetCount`：正整数。

这样可以同时表达：

- 工作日每天背 20 个单词：工作日计划 + 每计划日目标 20。
- 每周任意跑步 3 次：每天可执行的计划 + 每周目标 3。
- 累计阅读 100 次：每天可执行的计划 + 生命周期目标 100。

### 3. 记录

- `HabitRecord` 继续属于 Habit，`occurredOn` 使用设备本地日期语义。
- 普通习惯只允许在计划日写入记录；非计划日的历史记录不删除，但不作为新行为的依据。
- 同一日期的多条记录继续按 `count` 求和；实现阶段应增加清晰的写入命令，避免 UI 自行猜测“增加、撤销还是直接完成”。

### 4. 提醒

- `HabitReminder` 首版仍只保存 `localTime` 与 `isEnabled`。
- 提醒日期不再单独存一份星期集合，而是从 `HabitSchedule` 派生，保证执行日与提醒日不漂移。
- shared 的 `HabitReminderPlanner` 产出具体、稳定标识的未来提醒实例；平台层只负责 replace/cancel。
- 完成目标、归档、删除、修改计划、修改时间、应用启动与同步落库后都需要重新规划提醒。
- 提醒规划必须考虑平台上限，按时间排序并使用有界滚动窗口；窗口大小在实现阶段由平台能力测试确定。

### 5. 艾宾浩斯

- `EBBINGHAUS_CLASSIC_V1` 的偏移固定为 `[0, 1, 2, 4, 7, 15, 30, 60]`，以 `startOn` 为第 0 天。
- 迁移后的 `startOn` 取旧 Habit `createdAt` 的设备本地日期，确保已有用户的复习日不整体平移。
- 复习日目标固定为 1，非复习日不可打卡且不进入当日完成率。
- 保留“还有 N 天开始”“还有 N 天复习”“已完成记忆周期”三种提示。
- 提醒只在复习日且该日尚未完成时产生。
- “重启记忆曲线”必须是显式操作，不能通过修改 `createdAt` 或普通编辑隐式发生。

## 用户场景

1. 用户创建“背 20 个单词”，选择“工作日”、每天目标 20、08:30 提醒；周一到周五可打卡并收到提醒，周末不显示为待完成、不计入完成率、也不提醒。
2. 用户创建“每周跑步 3 次”；本周任意计划日的记录共同累计，达到 3 次后本周状态为完成。
3. 用户创建艾宾浩斯习惯；系统沿用经典 0/1/2/4/7/15/30/60 天复习日，非复习日展示下一次复习提示但不能打卡。
4. 老用户升级后，已有 DAILY、WEEKLY、MONTHLY、TOTAL 和 EBBINGHAUS 记录保持可见，累计结果与迁移前一致或符合已明确记录的行为变更。
5. 用户通过 LiFi 说“工作日每天背单词，早上八点半提醒”；确认卡应明确显示周一至周五与提醒时间，再创建 Habit。

## 旧数据迁移

| 旧合同 | 新重复计划 | 新目标 | 备注 |
| --- | --- | --- | --- |
| `DAILY + PER_PERIOD` | `WEEKLY_DAYS` 全 7 天，`startOn=createdAt` 本地日 | `SCHEDULED_DAY / timesPerPeriod` | 保持每日目标 |
| `WEEKLY + PER_PERIOD` | `WEEKLY_DAYS` 全 7 天 | `WEEK / timesPerPeriod` | 保持“本周累计 N 次”，不擅自猜测具体周几 |
| `MONTHLY + PER_PERIOD` | `WEEKLY_DAYS` 全 7 天 | `MONTH / timesPerPeriod` | 保持“本月累计 N 次” |
| 任意 `TOTAL` | 按旧 period 转换重复计划 | `LIFETIME / totalTarget` | 缺失时以 `max(已完成总量, 1)` 固化目标并记录迁移异常 |
| `EBBINGHAUS` | `EBBINGHAUS_CLASSIC_V1`，`startOn=createdAt` 本地日 | `SCHEDULED_DAY / 1` | 保持经典复习日与既有记录 |
| `ONCE` | `ONCE`，日期暂取 `createdAt` 本地日 | `SCHEDULED_DAY / 1` | 当前 ONCE 行为不稳定，需在规格冻结前确认 |

- 新合同属于 breaking change，应升级 SQLDelight schema 与完整快照协议版本，不在 V3 JSON 上静默改变字段含义。
- V2 Task carrier 恢复逻辑保留为只读迁移入口，恢复后直接生成新 Habit 合同，不重新建立 Task 关联。
- 现有 `habit_schedule_item` 在代码中没有业务写入者，也没有可证明的产品语义；新合同移除该表。迁移需记录被丢弃行数并在导入报告中展示，不让旧行参与新计划。

## 验收标准

- KMP schema、domain、repository 和 ViewModel 中不存在 Habit 到 Task UID 的外键或运行时依赖。
- 周一至周五计划在所有时区下只对 ISO 周一至周五返回 due，周末写入请求被领域层拒绝。
- 非计划日不进入当日完成率分母，未来日期不可打卡。
- `SCHEDULED_DAY`、`WEEK`、`MONTH`、`LIFETIME` 四种窗口均有跨月、跨年和周一边界测试。
- 艾宾浩斯对第 0/1/2/4/7/15/30/60 天返回 due，其他日期返回正确下一次提示；60 天后返回周期完成。
- 艾宾浩斯升级前后锚点和历史完成记录不变。
- shared 生成的同一组提醒实例在 iOS、Android、HarmonyOS 使用相同业务日期和稳定标识；非计划日没有提醒。
- 归档、删除、完成、改星期、改时间和同步覆盖后，不残留旧提醒。
- Android 在提醒投递、设备重启、应用升级、日期/时区变化后重新规划时，仍遵循同一 shared 计划；Widget 在非计划日不可打卡且显示正确提示。
- Snapshot 新版本 round-trip、旧数据库迁移、V2 carrier 迁移和多端同步均有测试。
- 三端编辑器均提供每天/工作日/周末/自定义周几，编辑既有 Habit 不丢失计划。
- AI/LiFi 能正确解析并确认“工作日”“周末”“每周一三五”等表达。
- 文档、API reference 与三端展示文案反映最终行为。

## 风险与约束

- 本地日期与时区：`occurredOn/startOn` 是本地日；同步后不能因另一设备时区而改变已记录日期。提醒按当前设备时区重新生成。
- iOS 待处理通知数量有限，不能为每个 Habit 无限预生成；需要全局排序、上限和刷新策略。
- 当前三端存在多份艾宾浩斯和进度算法，切换 shared authority 时必须一次性清除旧调用路径，避免“双权威”。
- 旧 `WEEKLY/MONTHLY` 的每日完成率本身含糊。迁移要保留累计数，但新 UI 如何在日视图表示周期目标需产品确认。
- 当前 `toggleRecord` 同时承担增加和撤销，并且不同 period 行为不同；新命令语义需要先冻结再实现。
- 这是 breaking change。开始表结构和快照改造前，按仓库规范创建一次全工作区保护提交。
- 大型实现完成后再统一编译；文档阶段只做规格校验与静态审计。

## 已冻结的产品决策

1. **非计划日展示**：普通周几习惯从主待办列表隐藏且不计分；艾宾浩斯进入“下一次复习”区域显示倒计时。
2. **数量追踪**：区分 `CHECK_OFF` 与 `QUANTITY`；数量模式支持目标值、可选单位、默认增量、输入增量和直接完成。
3. **周期目标的日视图**：每周/每月/累计目标展示在周期进度区，不进入每日完成率。
4. **ONCE**：只保留旧数据兼容，不在新编辑器提供创建入口。
5. **艾宾浩斯付费边界**：三端统一为 Pro 创建/重启能力；已有 Habit 始终允许查看、打卡、归档与导出。
6. **Android 大文件**：不要求先重构 `AddDDLActivity`；它只做最小接线，领域规则必须留在 KMP。

详细用户行为与技术落地分别见 `prd.md` 和 `trd.md`。
