# Habit 专注实现与验收

日期：2026-10-12。范围：Core 与 Android；未提交或推送。

## 实现范围

- 类型化 Task / Habit 目标，设备内单个活动计时，正计时 / 倒计时 / 暂停 / 恢复复用原算法。
- 普通结束只记录时间；结束并打卡只确认完成，不撤销 CHECK_OFF。QUANTITY 输入实际正整数增量，取消不结束，倒计时在输入中结束仍可按原会话补打卡。
- 启动日作为打卡归属日；非计划日可专注不可打卡。元数据变更后拒绝过期数量输入，关闭后重新确认。
- SQL 联合事务、持久化 habitRecordedAmount 幂等标记；打卡失败不会留下半条专注历史或清掉计时。更正 / 删除专注与撤销打卡独立。
- Android 现代 / 经典习惯行右滑归档和专注，左滑编辑和删除；展开点击先收起、多选禁用、宿主单行展开、删除确认。复用 Task 手势容器与现有卡片，物理方向不随 RTL 翻转，并提供四项具名无障碍操作。
- 现有选择器、计时页、浮动栏、通知支持 Habit；数量不从通知直接提交。Habit 结果通知按 sessionUid 打开专注页，不打开 Task。
- 统计总专注包含两类目标，排行标识 Habit；任务成果数仍只计 Task。保留总览 / 趋势 / 上月及项目 compose-charts，未新增二级 Tab。

## 数据升级与发布约束

数据库 schema 8，新增 7.sqm 保留旧 Task 历史和活动计时。Snapshot / changelog 数据格式 6 强制输出版本字段；新端可迁移旧 Task 数据，备份保留 Habit 类型及幂等标记。旧端严格拒绝新格式，不能与新版继续互写；参与同一同步的其他客户端必须升级 Core。格式伪报 5 的 Habit 数据和未知版本在写库前拒绝。

已建立 Core / Android 独立本地安全快照，引用与哈希见 tasks.md。安全快照不是发布包；回退需先另存本轮及其他后续变更，按明确文件恢复，不能直接 reset 整个工作区。数据格式已写出 6 后，不应仅降级应用继续同步。

## 验证证据

- Core 分批 73 项定向测试通过：49 项专注/统计/备份，10 项结果通知/服务器映射，14 项原有习惯规则/编辑/列表。覆盖同 UID 隔离、数量幂等、输入中自动结束、后续新计时保护、元数据失效、归档/远端删除、跨午夜/冷 ViewModel 恢复、SQL 异常回滚、schema 7→8、统计与同步备份。Habit 服务器映射往返明确验证 typed target、启动日和实际数量证据。
- Core iOS Arm64 / OHOS Arm64 Kotlin 桥接最终编译通过。Android `testDebugUnitTest / assembleDebug / assembleDebugAndroidTest` 成功，309 项单元测试零失败。
- Android 浅色 100% 字体、深色 130% 字体各 5 项 UI 测试通过：数量取消/确认、输入中到点补打卡、现代/经典行的 LTR/RTL 双向滑动与四项无障碍语义、原三个统计页签包含 Habit 且任务成果数不变、专注空态。共 10 次设备测试通过。
- 截图位于 screenshots/light 和 screenshots/dark-large，已实际查看数量弹层和排行。来自本轮独立 Pixel_9_Pro 只读模拟器 emulator-5556，没有清空用户应用数据库；夹具均独立命名，测试后仅软删除自己的夹具。未操作既有 emulator-5554。
- 规格校验与两仓库 `git diff --check` 通过。提供的文件规模脚本缺失，替代机械检查相对安全快照的现存 Kotlin 变更文件：Core 72 个、Android 56 个，最大有效行近似计数分别 421、864，均低于 1000。

复现：Core `:shared:linkDebugTestMacosArm64` 后分别运行 test.kexe 的 Gradle filter：`*HabitFocus*,*FocusTimer*,*FocusStatistics*,*BackupService*,*ChangeLogSync*,*SnapshotV3*`；`*FocusResultNotification*,*ServerSnapshotMapper*`；`*HabitEngines*,*HabitEditorViewModel*,*HabitListViewModel*`。Android 设备测试类为 HabitFocusInstrumentedTest、FocusStatisticsInstrumentedTest。

最终日志：`/private/tmp/habit-focus-android-final.log`、`habit-focus-core-final.log`、`habit-focus-native-targeted.log`、`habit-focus-native-notification.log`、`habit-focus-native-existing-habits.log`、`habit-focus-instrumented-final-light.log`、`habit-focus-instrumented-final-dark-large.log`。这些临时日志可被系统清理，本文保留验收结论及复现方式。

## 未覆盖 / 后续

- iOS / HarmonyOS 原生习惯入口和 UI 未实现；只提供共享桥接新命令。
- 本轮使用专用模拟器，不替代真机后台、省电限制或系统 Live Updates 验证。
- 尚未运行真实旧 APK 与新端的混合设备同步；目前验证的是格式门禁、旧数据迁移及往返合同，不承诺混合版本兼容互写。
- 定向测试不是整个 Core 项目所有测试均通过的声明。
- 扩大单次 Kotlin Native 测试集合时曾停留在 GC 根扫描；已采样 `/private/tmp/habit-focus-native-hang.sample.txt`，终止仅本轮测试进程，并分批通过上述集合。未修改应用 GC 设置，也未将停顿判定为已修复的运行时问题。
- 原有任务成果四列长英文标签在 130% 字体下换行欠佳，见 dark-large/fusion-distribution.png；新增 Habit 排行与数量输入未遮挡，但这项既有排版仍需后续优化。无障碍本轮验证语义动作，不代替真实 TalkBack 手动验收。
