# 习惯绑定专注 Module Plan

## 模块拆分

| 模块 | 位置 | 职责 |
| --- | --- | --- |
| Target contract/policy | shared/domain/focus | 类型化目标、Task/Habit 资格、命令/结果 |
| Timer application | shared/service/focus | 计时复用、联合事务与幂等编排 |
| Persistence/interop | shared/db/repository/focus、habit、sqldelight、sync、bridge | 迁移、目标映射、记录关联、同步与版本保护 |
| Statistics | shared/domain/statistics、db/repository/statistics | 通用目标聚合与习惯元数据订阅 |
| Presentation | Android ui/focus、ui/main/simplified、ui/overview、notification | 习惯入口、数量确认、通用显示与通知 |

## 平台映射

- Android：`app/src/main/java/com/aritxonly/deadliner/ui/focus` 复用计时流程；`ui/main/simplified/HabitComponents.kt`、`HabitDisplayLayout.kt` 与主宿主接线；`ui/overview` 保留现有三个页签。
- Android 通用容器为 `ui/main/SwipeableItemCard.kt`，`SwipeableTaskCard.kt` 保留 Task 公开适配 API，继续复用 TaskCardSwipeRules。内容插槽适配原现代/经典 Habit 行；右滑归档/专注、左滑编辑/删除，具名无障碍操作；不引入另一套滑动库。
- iOS：`Deadliner/Features/Focus` 桥接；习惯入口位于 `Features/Home/Components/HabitItemCard.swift`，后续落地，不用 Task 详情当习惯入口。
- HarmonyOS：`entry/src/main/ets/pages/routes/focus`、`pages/routes/main/HabitListSection.ets` 与控制器；后续改 .ets 前加载 ArkTS/ArkUI 技能。
- Core：共享目标/规则与迁移先行，原有 iOS 状态桥接及 OHOS C API 必须明确版本策略，不能默默改变所有客户端合同。

## 文件拆分策略

`FocusTarget.kt` 负责目标与规则、`HabitFocusOperations.kt` 负责联合事务、`HabitFocusRecordDialog.kt` 负责数量输入、`HabitEditSupport.kt` 负责原编辑入口适配；统计排行保留在 FocusStatisticsCards，不扩大 Dashboard。所有本轮新增核心文件低于 1000 有效行，编译与规模检查记录在 acceptance.md。
滑动容器/动作背景/结算规则独立于 Task 与 Habit 的业务适配；原 Task 外观和公开调用保留，习惯只配置自己的四个动作。宿主管理展开行身份，避免 Task/Habit 同 UID 状态串联。

## 风险点

- 旧同步解码拒绝新字段，协议冻结是业务代码迁移的先决条件。
- 现有习惯操作含 toggle 撤销语义，联合结束必须使用只增加/确认的操作。
- 数量输入期间自动到点与幂等结果页记录需要同一个持久化关联，不能两个临时分支各写一次。
- 当前工作区有多项并行改动，破坏性升级前只做经过确认的回退保护，不擅自纳入其他工作。
