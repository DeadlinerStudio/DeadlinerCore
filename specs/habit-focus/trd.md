# 习惯绑定专注 TRD

状态：2026-10-12 Core / Android 已实施；原生 iOS / HarmonyOS UI 后续适配。项目类名 SnapshotV3 保留，数据格式版本为 6。

## 1. 通用目标合同

- 引入 FocusTargetRef(kind=TASK/HABIT, uid)，显示名称快照与目标引用分离。不能把 habitUid 写入 taskUid 或依赖 UID 前缀识别类型。
- 服务/活动计时/历史/快照/统计使用同一目标身份；纯计时算法仍只计算时间，目标资格由专门 policy/resolver 提供。
- 旧 Task 启动与查询 API 保留适配层；Task 完成命令和 Habit 记录命令保持不同业务语义，不用含糊的统一 complete。

## 2. 习惯联合记录

- StartHabit 携带 habitUid、mode、plannedDurationSeconds；FinishAndRecordHabit 携带 sessionUid、amount 与可选 expectedHabitUpdatedAt。目标和日期从该活动/已保存会话读取，不信任客户端额外传入的目标或日期。
- 不使用 HabitRecordCommand.CheckOff/ApplyPrimary 直接写入，因为已有动作会清空或撤销完成。构造仅确认完成的计划：已完成 NO_OP，否则依目标窗口计算合法本次增加量。
- QUANTITY 使用经过校验的实际 amount；沿用现有 Add/Set 规则，不拿 elapsedSeconds 作为 count。
- HabitRepository 提供事务内校验/写入能力，FocusTimerService 在同一数据库事务保存专注、写入习惯记录/变更日志、持久化联合操作关联并清理活动计时；提交后再通知同步与更新闹钟/通知。
- FocusSession 持久化 target、habitOccurredOn、habitRecordedAmount（null 未联合记录，0 已确认但无新增，正数实际增量）。sessionUid 为幂等身份；支持进程恢复与倒计时先结束后的结果页补录，不靠内存 busy 标志。
- 无有效时长时按现有规则拒绝保存专注；不要仅因联合按钮制造 0 秒会话或绕过计时保存要求。
- 非计划日/归档/删除/非法数量失败时不部分写入。数据生命周期独立，删除 FocusSession 不撤销 HabitRecord。
- HabitRecord 继续遵循既有多设备同步冲突规则，不在本轮承诺跨设备数量强一致或全局单计时。

## 3. 持久化与兼容

- schema 8 / 新增 7.sqm：focus_session 增加 target_kind、habit_uid、habit_occurred_on、habit_recorded_amount，active_focus_timer 增加前三项；旧 Task 默认 TASK，保持 UID、时间锚点、时长及软删除。历史名称仍复用原快照列，提供 targetTitleSnapshot 别名；Habit 的 legacy taskUid 必须为空。
- 不修改已发布迁移文件；新增迁移并同步初始 schema、SQL 查询及实体 mapper。
- FocusSession 被 Snapshot、服务器映射、WebDAV、备份和 changelog 使用；所有路径需覆盖 Habit 目标及关联字段。
- SnapshotV3Root / ChangeLogSyncPayload 的版本升为 6，并使用 EncodeDefault(ALWAYS) 强制输出，防止默认 JSON 省略版本令旧端误认格式。新端迁移旧 3/4/5 数据；旧 5 缺省版本由 changelog 入口按 5 解释。声称 5 的 Habit 会话拒绝；未知版本拒绝后才允许写库。备份保留新目标及幂等标记。未运行实际旧客户端混合同步；旧端严格拒绝 6，不支持继续互写。
- 活动计时仍是设备本地状态，不参与多设备同步。

## 4. 服务、展示与通知

- Android 从 `ui/main/SwipeableTaskCard.kt`、`TaskCardSwipeRules.kt` 提取/复用通用滑动手势和动作背景，通过 content slot 容纳原 HabitRow/HabitRowClassic；原 DDLItemCardSwipeable 保留 Task 适配 API，不能用 Task 卡片直接替换习惯卡片。
- 配置两组动作：右滑 ARCHIVE/FOCUS，左滑 EDIT/DELETE；两侧为两动作宽度。动作处理分别绑定 HabitListViewModel、通用 Focus 入口和现有 Habit 编辑/删除确认，不借用 TaskAction。
- activeSwipeKey 在宿主统一管理，用 `task:<uid>`、`habit:<uid>` 防止不同实体同 UID 碰撞；现代/经典列表复用一个协调器，切日期/页签/多选时清理。滑动偏移仍为组件暂态，业务状态不放入手势组件。
- 沿用项目已有混合主题及页面宿主（Miuix 0.9.4-rc01），不增加 Scaffold、独立 blur 或新的手势库。复用 Task 的阈值/动画与可访问操作，验证 RTL 方向映射，不把 physical left/right 与 leading/trailing 混为一谈。
- HabitFocusPolicy 判定可启动/记录，HabitFocusOperations 编排目标上下文、联合事务、失效收尾。Habit 是否 due/complete 只影响联合记录，不影响普通计时资格。
- 目标归档/删除、恢复、倒计时收尾与联合记录按 sessionUid 去重；目标失效使用对应的结果语义，不能伪装为 TASK_BECAME_INACTIVE。
- ViewModel 提供目标类型、canFinishAndRecord、数量输入上下文与结果状态，平台不自行复制 HabitRecordCommandPolicy。
- 浮动栏/计时通知/结果通知改读通用目标；Habit 不发送 OPEN_TASK 或 MARK_COMPLETE，不在通知中无确认地提交数量。
- iOS 状态桥接增加 startHabitStopwatch、startHabitCountdown、finishAndRecordHabit；OHOS C API 增加 start_habit_stopwatch、start_habit_countdown、finish_and_record_habit 及新请求字段。Kotlin 桥接编译通过不等于原生 UI 已验收；原生 UI 仍待适配，升级时必须同步更新 Core 数据合同。

## 5. 统计

- 新增通用目标筛选/排行及 habitCount/targetCount；既有 taskCount 只算 Task，tasks 不能掺入 Habit。
- 分类解析订阅 Habit 元数据，Habit 改名/分类变更刷新，删除后使用快照。总时长与分类小计不重复计算。
- 日/自然周/rollingWeekly/月范围、补零、上月比较继续沿用现有引擎；不修改任务成果聚合。

## 6. 验证矩阵

- 纯规则：目标类型/UID 冲突、非计划日、已完成、数量合法性、跨日/时区及重复确认。
- SQLite：联合操作原子性、幂等、失败回滚、持久化恢复；迁移历史及活动 Task 数据。
- 同步/备份：旧数据导入、新目标往返、严格版本保护、软删除与联合证据保留。
- UI：右滑归档/专注、左滑编辑/删除两动作、点击收起不打卡、多选禁用、单行展开、删除确认、两种习惯样式及 RTL；习惯行计时不打卡，点击打卡/长按仍正常，双目标单计时，取消数量、输入中到点、后台恢复、失效目标、深浅色大字体。
- 回归：原 Task 结束并完成、暂停/继续、会话更正/删除、统计及通知不受影响。
