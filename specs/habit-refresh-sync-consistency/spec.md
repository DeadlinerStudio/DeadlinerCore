# Habit Refresh and Sync Consistency

## 背景

Habit 已由 KMP 统一持久化、进度计算与展示投影，但当前仍有两处行为断点：Android 下拉刷新只同步读取本地缓存，没有执行 WebDAV 同步；数量习惯在达到目标后继续点击仍会累加，三端缺少统一的“下一次点击归零”语义。真实远端变更日志已经包含 HarmonyOS 写入的 Habit Record，因此 Android 不显示的直接风险是同步后 KMP ViewModel 缓存未重新加载，另一个风险是 Habit 聚合版本未随 Record 变化推进。

## 目标

- Android 主列表下拉时执行一次 KMP WebDAV 同步，并在同步完成后重新加载 Task 与 Habit 的 KMP 列表状态。
- 无论 CHECK_OFF 还是 QUANTITY，主操作在当前目标窗口已经完成时，下一次点击将当前目标窗口内记录归零；未完成时按既有增量规则推进，且不产生超过目标后无法回退的状态。
- Habit Record 的新增、更新与清除必须推进 Habit 聚合同步版本，HarmonyOS、Android、iOS 对同一远端快照得到一致打卡状态。
- 同步取消仍保持协程取消语义，不作为普通失败吞掉。

## 非目标

- 不修改 WebDAV 服务端协议和文件路径。
- 不重做三端 Habit 卡片视觉样式。
- 不改变周几排期、目标窗口、艾宾浩斯算法或提醒计划。
- 不处理 `AddDDLActivity` 的结构性重构。

## 用户场景

- 用户在 HarmonyOS 为今天的习惯打卡，随后在 Android 下拉列表；Android 完成同步并立即显示相同进度。
- 数量目标为 20、默认增量为 5 时，点击序列为 0、5、10、15、20、0，不会进入 25。
- 打卡型目标完成后再次点击，当前目标窗口回到 0；之后可以重新打卡。
- 云同步关闭或凭据缺失时，下拉仍会重新读取本地 KMP 列表，并正常结束刷新指示器。

## 验收标准

- KMP 领域测试覆盖完成后归零、数量增量封顶前推进、日/周/月/总目标窗口的完成判断。
- KMP 数据库同步测试证明仅 Habit Record 变化也会让聚合版本胜出并跨设备合并。
- Android `PullToRefreshBox` 的刷新生命周期由独立状态驱动，不再依赖瞬时的 KMP `isLoading`；刷新协程按“同步 -> Task loadNow -> Habit loadNow”收尾。
- Android 编译通过；Core 的目标单测和 Android target 编译通过。
- 附件 `kmp-changelog-v1 (2).json` 可被当前快照合同解析，其中 HarmonyOS Record 不因默认字段省略而失效。

## 风险与约束

- Habit 当前采用聚合 LWW：Record 内嵌在 Habit 文档，不能只比较 Habit 自身 `updatedAt`。
- 周/月/总目标的完成状态来自目标窗口聚合；归零清除该窗口内的有效记录，窗口外历史保持不变。
- Android 仓库位于 `/Users/aritxonly/Codes/ProjectDeadliner/Deadliner`，需保留其现有未提交改动。
- `DeadlinerPersistenceCApi.kt` 仍按既有批准列入大文件白名单；本批不向其中加入领域规则。
