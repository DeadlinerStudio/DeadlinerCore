# Habit Scheduling Redesign Tasks

## 阶段 1：规格冻结

- [x] 从 `refactor/kmp` 创建 `codex/habit-redesign`。
- [x] 审计 KMP Habit/Task 独立性、schema、repository、ViewModel、snapshot 与通知边界。
- [x] 审计 iOS、Android、HarmonyOS 当前编辑、列表、提醒与艾宾浩斯行为。
- [x] 纠正 Android 权威仓库为 `~/Codes/ProjectDeadliner/Deadliner`，补充审计 KMP composition root、Receiver、开机恢复与 Habit Widgets。
- [x] 写出 `spec.md` 与 `module-plan.md` 初稿。
- [x] 通过 PRD 冻结非计划日展示、数量追踪、周期目标日视图、ONCE 与艾宾浩斯付费边界。
- [x] 通过 TRD 冻结新领域合同和 breaking migration 规则初稿。
- [x] 开始 breaking change 前创建全工作区保护提交（`617639c`）。
- [x] 记录 `DeadlinerPersistenceCApi.kt` 既有超 1000 行白名单批准；限定为桥接接线例外，不允许新增领域算法。

完成标志：待确认问题已决策，验收标准与迁移矩阵不再含歧义。

## 阶段 2：基础设施

- [x] 新增 `HabitSchedule`、`HabitGoal`、`HabitTracking` 与 validation。
- [x] 实现并测试 `HabitScheduleEngine`。
- [x] 实现并测试 `HabitProgressEngine`。
- [x] 实现并测试 `EBBINGHAUS_CLASSIC_V1` 固定计划。
- [x] 定义 CheckOff/Add/Set/Clear 记录命令及领域拒绝结果。
- [x] 修改 SQLDelight schema/query/repository mapper。
- [x] 用真实 SQLite 旧表样本演练 v4 -> v5 migration、字段映射、FK check 与诊断计数。
- [x] 升级 Snapshot/同步协议门槛到 V4，并保留 V2 carrier 只读迁移测试。
- [x] 移除 `habit_schedule_item`，将非空旧数据行数写入 `migration_diagnostic`。

完成标志：commonTest 和数据库迁移测试通过，新合同不依赖 Task。

## 阶段 3：功能实现

### 共享应用层

- [x] 重构 `HabitEditorViewModel` 表单与校验；保留 Android 旧接线的临时 source-compatible 方法。
- [x] 重构 `HabitListViewModel`，删除内联日期/艾宾浩斯重复算法。
- [x] 将“主操作为 CHECK_OFF 或按 defaultIncrement 增量”及未来日期可操作性加入 shared presentation/command 边界。
- [x] 实现 `HabitReminderPlanner` 与提醒生命周期测试。
- [x] 更新 `NotificationGateway` 合同及 recording fake，输出权威具体本地实例。
- [x] 更新 Search shared 消费者，复用 `HabitPresentationEngine` 输出 due/progress/hint；当前 shared Archive 仅管理 Task，Habit 归档展示留在平台消费层（Overview 已完成）。
- [x] 更新 AI proposal、PromptBuilder、LiFi skill schema、tool normalizer 与 executor；旧调用返回兼容警告。
- [x] 更新 iOS typed state 与 HarmonyOS JSON bridge DTO；HarmonyOS state 额外输出 shared reminder plans。

完成标志：shared 层能独立回答任意日期的 due/progress/hint，并产出确定提醒计划。

### 平台实现

- [x] Android：确认现有脏工作区边界后，从 `~/Codes/ProjectDeadliner/Deadliner` 迁出 `codex/habit-redesign`，保留已有 Task/Glance 脏改动。
- [x] Android：在不重构 `AddDDLActivity.kt` 的前提下完成列表/搜索/Widget 展示、Receiver 续期和系统提醒投递；列表、日期 actionability、Widget、AI bridge、Archive 与原生搜索均已接通新合同，并通过编译/单测。
- [x] iOS：编辑器支持每天/工作日/周末/自定义周几并回填新 `schedule/goal/tracking` 合同；Home、Search、Category、Archive、Widget 与 Watch 均消费 shared due/progress/hint/主操作，具体日期提醒消费 shared plan。Habit 相关 Swift 已通过编译阶段；完整 App 构建仍被本功能范围外的既有 Swift/工具链兼容问题阻断。
- [x] HarmonyOS：表单、列表、搜索、提醒改为 shared authority；每天/工作日/周末/自定义周几可在新建与编辑表单回填并保存，列表、搜索、周概览、主操作和具体日期提醒均消费 KMP 状态。
- [x] 三端清除旧的艾宾浩斯、周期边界和固定每日提醒算法。
- [x] 对齐三端文案、默认值与无障碍标签；计划默认每天、目标默认每计划日 1 次、追踪默认打卡、提醒默认关闭，周几选择与 Habit 卡片均提供可读选择/进度/操作状态。

完成标志：同一迁移样本在三端显示相同计划、进度、艾宾浩斯提示与提醒日。

## 阶段 4：验证与回写

- [x] 运行 shared 单元/集成/迁移/同步测试（62 项通过）。
- [x] 完成一次大型实现批次后统一编译 Android、iOS Simulator、OHOS KMP targets。
- [x] 部署新版 Android AAR 并通过 `:app:compileDebugKotlin`；现存旧编辑器/Archive/AI bridge 兼容 API 均以 deprecated warning 追踪。
- [x] HarmonyOS 编译显式设置 `DEVECO_HOME`；清理既有 Native 增量状态后，`assembleHap` 于 2026-08-20 成功。周几编辑器追加后在项目日常 zsh/DevEco 环境下再次完成 ArkTS、打包和签名全链路构建。
- [x] 执行规格校验（`validate_feature_spec.py` 通过）。
- [x] 执行大文件扫描；Kotlin 仅 `DeadlinerPersistenceCApi.kt` 为 1400 行并按已批准白名单处理，其余最大生产文件 424 行。
- [x] 更新 `docs/API_REFERENCE.md` 的 Habit、progress、AI/LiFi 与 reminder 合同；平台迁移说明随原生接线批次继续更新。
- [x] 用真实旧数据库/快照样本做升级演练和回滚验证；仓库脚本从 SQLDelight `1.db` 构造 v4 样本，执行真实 `4.sqm`，校验映射/FK/诊断并恢复迁移前备份；V2 JSON 样本额外断言新合同。
- [x] 持续回写 spec/module-plan/tasks；2026-08-22 已冻结三端最终行为、验证证据与已知范围外构建阻塞。

完成标志：验收标准全部有自动化或人工证据，文档与实现一致。
