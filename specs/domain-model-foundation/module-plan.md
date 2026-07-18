# Deadliner Shared Domain Model Foundation Module Plan

## 模块拆分

- `domain/task`: Task、TaskSubtask、TaskState、状态机。
- `domain/habit`: Habit、HabitRecord、周期、目标、提醒与可选复习计划。
- `domain/category`: Category 与预设分类。
- `domain/sync`: EntityVersion、Tombstone、ChangeSet、同步实体类别。
- `domain/memory`: 用户画像与记忆片段快照。
- `domain/ai`: AI 提取结果和受限查询 proposal；不包含网络 DTO。
- `domain/account`: UserTier。

## 平台映射

- iOS/Android：直接消费 KMP 类型。
- HarmonyOS：按 JSON schema 和 golden fixtures 实现等价 ArkTS 类型。

## 文件拆分策略

- 每个 aggregate 只拥有自己的状态和子实体。
- Task 与 Habit 不共享基类或泛型 payload，避免重新引入伪载体。
- 单个文件控制在 300 行以内。

## 风险点

- KMP 导出到 Swift 时应优先保持简单 data class、String UID 和 enum，避免平台特定类型。
- Storage schema 尚未创建；不得让领域模型依赖 SQLDelight generated API。
