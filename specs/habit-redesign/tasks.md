# Habit Scheduling Redesign Tasks

## 阶段 1：规格冻结

- [x] 从 `refactor/kmp` 创建 `codex/habit-redesign`。
- [x] 审计 KMP Habit/Task 独立性、schema、repository、ViewModel、snapshot 与通知边界。
- [x] 审计 iOS、Android、HarmonyOS 当前编辑、列表、提醒与艾宾浩斯行为。
- [x] 纠正 Android 权威仓库为 `~/Codes/ProjectDeadliner/Deadliner`，补充审计 KMP composition root、Receiver、开机恢复与 Habit Widgets。
- [x] 写出 `spec.md` 与 `module-plan.md` 初稿。
- [x] 通过 PRD 冻结非计划日展示、数量追踪、周期目标日视图、ONCE 与艾宾浩斯付费边界。
- [x] 通过 TRD 冻结新领域合同和 breaking migration 规则初稿。
- [ ] 开始 breaking change 前创建全工作区保护提交。

完成标志：待确认问题已决策，验收标准与迁移矩阵不再含歧义。

## 阶段 2：基础设施

- [ ] 新增 `HabitSchedule`、`HabitGoal` 与 validation。
- [ ] 实现并测试 `HabitScheduleEngine`。
- [ ] 实现并测试 `HabitProgressEngine`。
- [ ] 实现并测试 `EbbinghausClassicPlan`。
- [ ] 定义明确的记录命令及领域拒绝结果。
- [ ] 修改 SQLDelight schema/query/repository mapper。
- [ ] 编写旧数据库到新合同的 migration test。
- [ ] 升级 Snapshot 协议并覆盖 V3/V2 carrier 输入迁移。
- [ ] 移除 `habit_schedule_item`，并实现非空旧数据的诊断与导入报告。

完成标志：commonTest 和数据库迁移测试通过，新合同不依赖 Task。

## 阶段 3：功能实现

### 共享应用层

- [ ] 重构 `HabitEditorViewModel` 表单与校验。
- [ ] 重构 `HabitListViewModel`，删除内联日期/艾宾浩斯重复算法。
- [ ] 实现 `HabitReminderPlanner` 与提醒生命周期测试。
- [ ] 更新 `NotificationGateway` 合同及 recording fake。
- [ ] 更新 Overview、Search、Archive 等 shared 消费者。
- [ ] 更新 AI proposal、PromptBuilder、LiFi skill schema、tool normalizer 与 executor。
- [ ] 更新 iOS/HarmonyOS bridges 和序列化 DTO。

完成标志：shared 层能独立回答任意日期的 due/progress/hint，并产出确定提醒计划。

### 平台实现

- [ ] Android：确认现有脏工作区边界后，从 `~/Codes/ProjectDeadliner/Deadliner` 的 `refactor/kmp` 迁出对应 Habit 分支。
- [ ] Android：在不重构 `AddDDLActivity.kt` 的前提下完成最小表单接线，并实现列表/搜索/Widget 展示、Receiver 续期和系统提醒投递；不得把领域规则写入 Activity。
- [ ] iOS：编辑器周几选择，Home/Search/Category/Archive/Watch 消费 shared 状态，提醒调度去重。
- [ ] HarmonyOS：表单、列表、搜索、提醒改为 shared authority；修改 `.ets` 前加载 ArkTS/ArkUI skills。
- [ ] 三端清除旧的艾宾浩斯、周期边界和固定每日提醒算法。
- [ ] 对齐三端文案、默认值与无障碍标签。

完成标志：同一迁移样本在三端显示相同计划、进度、艾宾浩斯提示与提醒日。

## 阶段 4：验证与回写

- [ ] 运行 shared 单元/集成/迁移/同步测试。
- [ ] 完成一次大型实现批次后统一编译 KMP 与三端，不对每个小 patch 重复编译。
- [ ] HarmonyOS 编译显式设置 `DEVECO_HOME`。
- [ ] 执行规格校验。
- [ ] 执行大文件扫描，并处理超过 1000 行的核心文件。
- [ ] 更新 `docs/API_REFERENCE.md`、架构文档和平台迁移说明。
- [ ] 用真实旧数据库/快照样本做升级演练和回滚验证。
- [ ] 回写 spec/module-plan/tasks，使文档描述最终事实。

完成标志：验收标准全部有自动化或人工证据，文档与实现一致。
