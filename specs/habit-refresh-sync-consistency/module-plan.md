# Habit Refresh and Sync Consistency Module Plan

## 模块拆分

- `habit-domain`：在 `HabitRecordCommandPolicy` / shared 主操作中统一“未完成则推进、已完成则清除”的循环语义。
- `habit-application`：`HabitListViewModel` 只调用统一命令并在写后重建 shared state。
- `sync-snapshot`：`SnapshotV3Store` 以 Habit 与其全部 Record 的最新时间生成聚合版本，merge 后替换该 Habit 的内嵌 Record 集。
- `android-refresh`：Android 主列表提供单一 suspend 刷新协调器，负责 KMP 同步和两个列表的 reload；Compose 只维护刷新状态及手势。

## 平台映射

- KMP：`shared/src/commonMain/kotlin/com/deadliner/domain/habit`、`viewmodel/habit`、`sync/snapshot`、`db/repository`。
- Android：`app/src/main/java/com/aritxonly/deadliner/ui/main/shared` 与 `platform/kmp`。Modern/Simplified host 继续复用同一 `MainDisplay`。
- iOS：不新增平台算法，既有 Home/Search/Watch 通过 shared 主操作自动获得归零行为。
- HarmonyOS：不新增 ArkTS 算法，既有 KMP C bridge 通过 shared 主操作与快照聚合获得一致行为。

## 文件拆分策略

- 刷新编排若超过简单函数，新增小型 coordinator，不把网络与错误处理堆入 `MainDisplay.kt`。
- 归零规则留在既有小型 domain policy；平台不得检查 `isCompleted` 后自行选择 add/clear。
- 不扩大已超过 1000 行的 C ABI 文件；若无需桥接签名变化则完全不修改它。

## 风险点

- 仅在 UI 层清零会导致 Widget、Watch、HarmonyOS 仍不一致。
- 同步完成前 reload 会继续显示旧缓存；必须严格排序。
- 用 `taskState.isLoading || habitState.isLoading` 控制手势动画时，同步阶段没有 loading 变化，视觉上像“没反应”。
- 远端旧快照省略 Record 默认字段；解码必须保留序列化默认值兼容性。
