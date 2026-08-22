# Habit Scheduling Redesign Module Plan

## 模块拆分

### `habit-contract`

- 将当前单文件领域合同拆为 `Habit`、`HabitSchedule`、`HabitGoal`、`HabitRecord`、`HabitReminder`。
- 定义可序列化、跨 Swift/ArkTS/Android 友好的枚举与值对象；周几落库使用 ISO 7 bit mask，UI 层再映射为集合。
- 新建 Snapshot 协议版本和迁移 DTO，不复用旧字段名表达新含义。

### `habit-domain`

- `HabitScheduleEngine`：判断计划日、下一次计划日和计划结束。
- `HabitProgressEngine`：按日/周/月/生命周期窗口聚合记录。
- `HabitAlgorithms` + `HabitScheduleEngine`：唯一维护经典偏移与提示状态。
- `HabitCommandPolicy`：校验未来日期、非计划日、已归档状态及记录命令。
- 以上模块保持纯 Kotlin、无数据库和平台依赖，以 commonTest 做日期边界测试。

### `habit-infra`

- SQLDelight schema/migration、query 与 repository mapper。
- 旧字段到新合同的数据库迁移。
- Snapshot 新版本、V3/V2 输入迁移、changelog/sync 编解码。
- 明确处理或移除目前未成为业务权威的 `habit_schedule_item`。

### `habit-application`

- `HabitEditorViewModel` 负责编辑计划、目标和提醒，不自行计算日期。
- `HabitListViewModel` 只组合 domain engine 结果，删除重复的 `periodRange/scheduleState` 算法。
- `HabitPresentationEngine` 形成 List/Search/iOS typed state/Harmony JSON 的统一 due/progress/hint 投影。
- `HabitReminderPlanner` 从 Habit + Record 生成有界提醒实例。
- `NotificationOrchestrator` 负责 replace/cancel 生命周期。
- AI proposal、PromptBuilder、LiFi tool schema 与执行器改用新合同。

### `habit-presentation`

- 三端编辑器：计划预设、周几 chips、目标窗口、提醒摘要、艾宾浩斯说明。
- 三端列表/搜索/归档/Widget/Watch：消费 shared 派生状态，不重复计算艾宾浩斯或周期边界。
- 非计划日与周期目标的视觉行为在产品决策冻结后统一。

## 建议 shared 文件落位

- `shared/src/commonMain/kotlin/com/deadliner/domain/habit/Habit.kt`
- `shared/src/commonMain/kotlin/com/deadliner/domain/habit/HabitSchedule.kt`
- `shared/src/commonMain/kotlin/com/deadliner/domain/habit/HabitGoal.kt`
- `shared/src/commonMain/kotlin/com/deadliner/domain/habit/HabitRecord.kt`
- `shared/src/commonMain/kotlin/com/deadliner/domain/habit/HabitCommands.kt`
- `shared/src/commonMain/kotlin/com/deadliner/domain/habit/HabitProgress.kt`
- `shared/src/commonMain/kotlin/com/deadliner/domain/habit/HabitSchedule.kt`
- `shared/src/commonMain/kotlin/com/deadliner/service/NotificationOrchestrator.kt`（内含当前 planner；平台接线完成后可再独立拆文件）
- 现有 `HabitRepository.kt`、`HabitEditorViewModel.kt`、`HabitListViewModel.kt` 保留入口，但将规则下沉到上述小文件。

## 平台映射

### iOS

- Domain/Application：逐步移除 `HabitPeriodBounds`、`HomeViewModel.getEbbinghausState`、`SearchViewSupport.getEbbinghausState` 等本地规则权威，改用 shared 派生状态。
- Data：更新 `KMPHabitPresentationStore` 映射与 `KMPHabitStore` 新合同。
- Notification：`KMPHabitReminderScheduler` 改为消费 shared reminder plan；只保留 `UNUserNotificationCenter` 投递。
- Features：`HabitEditorSheetView`、Home、Search、Category、Archive、Widget/Watch 对齐计划日与提示行为。
- 当前进度（2026-08-21）：编辑器、Home、Search、Category、Archive、Widget、Watch 与本地提醒均已完成 shared authority 接线；`KMPHabitPresentationStore` 直接映射新合同，平台只保留展示、持久化桥接和系统通知投递。精确扫描已清除 iOS 本地艾宾浩斯、周期边界与固定每日提醒算法。

### HarmonyOS

- `common/kmp/KmpHabitRepositoryAdapter.ets` 更新 JSON/领域映射。
- KMP `deadliner_persistence_habit_state_json` 已追加 `selectedDate/visibleHabits/upcomingReviews/weekOverview/reminderPlans`，并提供按日期查询入口；原生适配器按此 DTO 消费，不自行计算。
- `common/repository/HabitRepository.ets` 只保留平台适配，不再维护独立周期算法。
- `pages/routes/main/HabitListSection.ets` 删除本地 `getPeriodBounds/getEbbinghausState` 权威，改用 KMP state。
- `utils/SearchUtils.ets` 删除重复艾宾浩斯逻辑。
- `utils/notification/ReminderScheduler.ets` 消费 shared reminder plan，不再固定 `[1..7]`。
- `modify/habit/*` 增加周几计划编辑并直接回填/保存 KMP `weekdaysMask`；`HabitReminderDialog.ets` 只编辑本地时间，提醒日期由 shared schedule 派生。

### Android

- 权威仓库：`~/Codes/ProjectDeadliner/Deadliner`，不是旧路径 `~/Codes/Android/Deadliner`。
- 继续以 KMP `HabitEditorViewModel/HabitListViewModel` 为主要状态入口。
- `app/src/main/java/com/aritxonly/deadliner/AddDDLActivity.kt` 与 `ui/editor/DeadlineEditorComponents.kt` 只做新 FormData 的最小接线，不再只传 `HabitPeriod`；本次不把 Activity 拆分作为前置条件。
- `platform/kmp/KmpV6Runtime.kt` 继续作为 Android composition root，负责注入新的 reminder planner/orchestrator。
- `notification/AndroidNotificationGateway.kt`、`KmpNotificationReceiver.kt` 与 `BootReceiver.kt` 接收 shared reminder plan；替换当前“单次 Alarm 投递后按每日续期”的隐式循环。
- `ui/main/simplified/HabitDisplayLayout.kt`、`HabitComponents.kt` 与 `ui/main/shared/MainSearchResultsContent.kt` 继续消费 KMP 派生状态，补充非计划日/周期目标展示。
- `widgets/HabitMiniWidget.kt`、`HabitMediumWidget.kt` 必须消费相同 due/hint 状态，并在日期、时间和时区变化后刷新。
- 当前进度（2026-08-22）：编辑器在 `DeadlineEditorComponents.kt` 内提供每天/工作日/周末/自定义周几，`AddDDLActivity` 仅把 mask 交给 shared editor；列表、搜索、归档、Widget、提醒和无障碍状态均消费 shared presentation/command 边界。

## 持久化与同步

- 推荐在 `Habit` 文档内嵌 `schedule`、`goal`、`reminder` 值对象；SQLDelight 可扁平化列存储，避免为只属于一个 Habit 的值对象引入独立同步实体。
- 迁移新增字段后再删除旧 `period/times_per_period/goal_type/total_target`，不长期维护双写。
- Snapshot 升级到新协议版本；旧 V3 与 V2 只作为输入迁移格式。
- `weekdaysMask` 范围必须为 `1..127`，bit 0 对应 ISO Monday，bit 6 对应 Sunday。
- `startOn/endOn/occurredOn` 使用 `YYYY-MM-DD` 本地日期；`createdAt/updatedAt` 继续使用 Instant 字符串。
- 同步冲突仍按 Habit 聚合 LWW；如果编辑 schedule 或 goal，应由同一 Habit 文档原子覆盖。

## 通知边界

- 用 `HabitReminderInstance(identifier, habitUid, title, fireAt)` 取代平台自行解释 period。
- identifier 至少包含 habit UID 与业务日期，确保单实例可替换/取消。
- shared planner 负责 due、完成状态和排序；平台负责权限、系统 API 和能力上限。
- App 启动、前台恢复、Habit/Record 变更和同步落库后刷新。
- iOS 的系统上限决定全局滚动窗口；Android/HarmonyOS 即使支持周期 Alarm，也不得绕过 shared due 规则另建第二套算法。

## 文件拆分策略

- 单个核心文件尽量不超过 1000 行有效代码。
- 当前 `HabitListViewModel.kt` 已混合命令、聚合、日期规则和 UI 模型，改造时必须拆分，不能继续叠加。
- HarmonyOS `HabitListSection.ets` 当前约 700 行，新增周几 UI 前先把状态计算移出页面。
- iOS `HabitEditorSheetView.swift` 当前约 500 行，周几选择器应作为独立组件。
- Android `AddDDLActivity.kt` 当前约 1111 行，产品方已批准本次不处理其既有体积；仅允许最小接线，不得向其中加入计划、进度或提醒算法。该例外不豁免新增核心文件的 1000 行守门规则。
- Android `DeadlineEditorComponents.kt` 当前约 572 行；周几选择器和目标编辑器分别拆成 feature components，避免跟 Task 编辑继续耦合增长。
- 日期/进度/艾宾浩斯算法各自保持独立测试文件，避免形成大型“HabitUtils”。
- 若任何核心文件超过 1000 行有效代码，先拆职责；确需逃逸时再申请批准。
- `shared/src/ohosArm64Main/.../DeadlinerPersistenceCApi.kt` 是迁移期既有 C ABI 聚合边界，当前 1400 行。产品方于 2026-08-19 批准将其加入本功能白名单，本轮允许保留 Habit DTO/reminder plan 的小范围接线；不得继续向其中加入领域或展示算法，后续按 C ABI 领域拆分治理。

## 风险点

- 新模型会同时影响数据库、Snapshot、AI tool schema、桥接 DTO 和三端 UI，必须按阶段提交，不能先改平台 UI 再补核心合同。
- iOS/HarmonyOS 当前仍有平台本地算法，迁移期间容易出现双重过滤或双重提醒。
- 多设备时区不同可能使 `createdAt -> startOn` 的一次性迁移结果不一致；迁移必须在已有本地数据库上固化一次并同步结果。
- `habit_schedule_item` 可能在真实用户快照中存在，即使当前代码没有写入者；删除前需要样本验证和迁移报告。
- AI 旧工具调用可能仍携带 period；新执行器要提供明确的兼容转换期或拒绝原因。
- Android 权威仓库当前存在与 Task 卡片和 Glance Widget 相关的用户未提交改动；迁出 Habit 分支前必须确认并保留这些改动，不能把保护提交或格式化扩散到无关文件。
