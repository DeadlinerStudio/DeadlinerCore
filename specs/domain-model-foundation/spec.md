# Deadliner Shared Domain Model Foundation

## 背景

当前 iOS 的 `DDLItem` 同时承担 Task 和 Habit carrier；Habit 还重复保存 Task 的名称、说明和分类。共享 core 必须先冻结独立领域模型，才能安全设计 SQLDelight schema 与 Repository。

## 目标

- 在 KMP `commonMain` 定义 Task、Habit、Category、Memory、AI proposal、同步版本和变更集。
- 移除 `DeadlineType`、`Habit.ddlId`、`habit_ddl_link` 和任何 Task/Habit 镜像字段。
- 保留现有 Deadliner 的任务状态机、习惯周期/目标/打卡、分类、日历链接、订阅等级和记忆契约。

## 非目标

- 不创建 SQLDelight 表或 Repository。
- 不迁移 SwiftData、iOS UI、Android UI 或 HarmonyOS RDB。
- 不把 HTTP chat request/response 或平台支付 SDK 放进领域层。

## 用户场景

用户创建习惯后，系统只保存 Habit 与 HabitRecord；创建任务后，系统只保存 Task 与 TaskSubtask。两个聚合可以共享 Category，但不能互相持有 ID 或复制字段。

## 验收标准

- commonMain 领域模型可由 Android、iOS target 编译。
- Task 与 Habit 模型之间没有引用。
- 任务状态机与现有 active/completed/archived/abandoned 行为等价。
- 所有可同步实体可携带稳定 UID、版本与 tombstone/change metadata。

## 风险与约束

- 旧 `legacyId` 只属于迁移层，不进入新领域 API。
- 时间暂以规范 ISO-8601 UTC 字符串表达，避免平台 Date 类型泄漏；storage adapter 后续负责转换。
- HarmonyOS 不运行 KMP；其实现必须遵守同一 JSON/domain fixture。
- 现存 `com.deadliner.model` 与 `ddl` / `habit_ddl_link` preview 是待替换的旧持久化原型，不是本领域契约的一部分。
