# iOS 专注原生落地与验收

验证日期：2026-10-12（Asia/Shanghai）。iOS 代码位于 `/Users/aritxonly/Codes/iOS/Deadliner`；共享导出适配位于 `shared/src/iosMain`。本次未修改、构建或部署 Android。

## 实现范围

- Task/Habit 类型化绑定，正计时、倒计时、暂停、恢复、结束、放弃，使用同一 Core 状态与持久化合同。数量习惯输入实际数量后联合打卡，二元习惯记 1；提前结束只记实际有效时长。
- 所有宿主共用单例状态，初始化恢复完成后才处理目标入口；冷启动通知缓存 UID 并核验记录存在、未删除，失效路由不会对另一条恢复记录联合打卡。
- RichMain 的 iOS 26+ 系统 `tabViewBottomAccessory` 仅活动会话存在时挂载；26.1+ 使用可启用 API。无 TabView 的 classic 页面使用原生 safe-area 栏；低版本 fallback 不引用 26+ environment key。不增加底栏页签。
- 原生历史更正/删除，Habit 联合操作冻结元数据版本，忙碌不能关闭输入，失败留在当前界面；删除专注记录不撤销原打卡。
- Task/Habit 归档、删除等活动目标变更入口拒绝直接修改并明确引导先解决当前计时；Habit Swift/Kotlin 变更边界捕获错误，避免未标注 Kotlin 异常跨 ObjC 边界。
- 专注统计融合现有总览、趋势、上月三页签，使用 Swift Charts；合计与 Task/Habit 拆分、选点反馈、零值空态、前台和持久化事实变化刷新。未做新看板。

## 编译与测试口径

正式工程：`/private/tmp/focus-native-ios-tests.log`。原有 Sync KMP Core 流程完成 iOS Arm64、Simulator Arm64、X64 Release XCFramework（`BUILD SUCCESSFUL`，27m51s），已部署到 iOS Vendor；应用 Swift 源码编译通过。正式 `xcodebuild test` 被未修改的 `DeadlinerTests/ToolCallExecutorBatchCompatTests.swift` 旧 `execute(toolName:argsJson:)` 调用阻塞（16 个兼容错误），退出 65。不能称正式完整 app build / 全套 test 成功。

在明确批准后使用 `/private/tmp/DeadlinerFocusValidation.xcodeproj` 进行定向诊断，差异仅为：指向正式源码目录；移除 App/Widget 的 Sync KMP Core 依赖以复用同一已部署框架；scheme 禁用并行；命令行仅排除上述旧 AI 测试文件。未修改正式工程、构建脚本或该旧测试。诊断 App 已实际链接、签名并在模拟器运行。

隔离设备：`DeadlinerFocusValidation`，iPhone 17 Pro / iOS 26.2，UDID `453363E2-F414-44AC-AA85-CB282E00DB85`。fixture 创建/当日记录重置只在此名称设备执行，且只处理两个明确命名习惯与测试任务；从未清空整个库。

最终定向单测：`/private/tmp/focus-native-ios-final-unit-retry.log`，4 个 case 通过，`TEST SUCCEEDED`，退出 0；当前 Swift 源码已编译、链接和签名。此前新增 fixture 使用错误 identity 属性的编译失败已修为 Swift `Habit.id`，不能用早先通过结果替代最终结果。

最终 UI 验收：`/private/tmp/focus-native-ios-final-ui.log`，2026-10-12 14:25 完成，2 个 case / 0 failures，`TEST EXECUTE SUCCEEDED`，退出 0。`testTaskHabitAndAccessoryFlows` 验收绑定目标、暂停全局栏、杀进程恢复、Task 联合完成、数量 12、二元 +1、倒计时提前结束；`testZHistoryAndStatisticsFlows` 验收更正、删除、总览/趋势/上月三个现有页签。使用 `test-without-building` 复用最终单测编译输出。

最终单测 xcresult：`/private/tmp/deadliner-focus-native-derived/Logs/Test/Test-Deadliner-2026.10.12_14-22-20-+0800.xcresult`；最终 UI xcresult：`/private/tmp/deadliner-focus-native-derived/Logs/Test/Test-Deadliner-2026.10.12_14-23-03-+0800.xcresult`。

诊断命令共同参数：`xcodebuild -project /private/tmp/DeadlinerFocusValidation.xcodeproj -scheme Deadliner -configuration Debug -destination id=453363E2-F414-44AC-AA85-CB282E00DB85 -derivedDataPath /private/tmp/deadliner-focus-native-derived -clonedSourcePackagesDirPath /Users/aritxonly/Codes/iOS/Deadliner/build/SourcePackages -disableAutomaticPackageResolution -parallel-testing-enabled NO -collect-test-diagnostics never EXCLUDED_SOURCE_FILE_NAMES=ToolCallExecutorBatchCompatTests.swift`。单测附加 `-only-testing:DeadlinerTests/FocusNativeTests test`；UI 附加 `-only-testing:DeadlinerUITests/FocusNativeUITests test-without-building`。

## 已读实际落库证据

只读检查隔离设备 SQLite，实际 UI 创建记录而非伪造时长：

| 目标 | 实际状态 / 打卡 | 专注模式 | 计划秒 | 实际秒 | 完成类型 |
| --- | --- | --- | --- | --- | --- |
| Task fixture | completed | STOPWATCH | 无 | 4 | FOCUS_AND_TASK_COMPLETED |
| 数量 Habit fixture | count=12 / COMPLETED | STOPWATCH | 无 | 8 | FOCUS_AND_HABIT_RECORDED，recordedAmount=12 |
| 二元 Habit fixture | count=1 / COMPLETED | COUNTDOWN | 1500 | 6 | FOCUS_AND_HABIT_RECORDED，recordedAmount=1 |

表内 Habit 的 COMPLETED 指单条打卡记录状态，不代表数量目标达成；数量习惯目标为 20，本次实际增加 12，未补齐目标数量。

前一轮 UI 的 Task 暂停→杀进程→恢复→完成、数量 12、二元倒计时实际时长均已通过。该轮后续历史锚点误命中继承 identifier 的 Image，测试定位失败而非业务错误；最终测试改为 Cell 整行和确切记录 UID。首次 Menu 定位误命中底层 Home 同名按钮的失败亦保留日志，随后修正可见选项与真实原生 Menu hit area。

实际截图：`screenshots/ios/manifest.json` 对应暂停全局 accessory、数量输入 12、联合记录历史、二元 6 秒历史与目标菜单。均为 XCTest 原生截图；未使用 mockup。

最终只读核验：第二轮 Task 完成记录 `6ED0DD3E-B3FA-46CD-8454-ED89595FD22F` 为 4 秒；数量联合记录 `04D482E0-86E8-42F9-988F-265D284775A9` 为 8 秒 / recordedAmount=12；二元倒计时 `1BF4C5FC-7991-41D6-A003-4A67C9786759` planned=1500 / duration=6 / recordedAmount=1。历史更正并删除的普通记录 `C4AB4727-FD2F-4AE0-B97D-D9269F5BA952` duration=1、is_deleted=1、FOCUS_ONLY；两习惯当前未删除打卡仍分别为 12、1。保留两轮合法专注历史，因此今日统计为总 36 秒、任务 8 秒 / 习惯 28 秒；软删除的 1 秒不计入。

最终截图共 13 张，完整命名对应 `screenshots/ios/final/manifest.json`，已目视检查三页签：

| 场景 | 图片 |
| --- | --- |
| 暂停原生 accessory | [截图](screenshots/ios/final/26BF4681-7B49-408A-ADDA-F269063BF3D0.png) |
| 数量输入 12 | [截图](screenshots/ios/final/91090BFF-CE10-47A4-9645-D4D7512E136E.png) |
| 二元倒计时实际 6 秒 | [截图](screenshots/ios/final/4EC00B61-CED2-404D-961E-609A637E831E.png) |
| 更正为 1 秒 | [截图](screenshots/ios/final/1401CEAF-1849-4F83-9482-834BC19E586B.png) |
| 删除后联合历史仍在 | [截图](screenshots/ios/final/CDE8A7D6-20A7-4288-894C-931EE6729D08.png) |
| 总览：36 秒与目标拆分 | [截图](screenshots/ios/final/C989A301-AA9B-4C9D-BB6C-50E99F56217F.png) |
| 趋势：近 7 天 36 秒 | [截图](screenshots/ios/final/43552C9F-4939-47E6-A51C-B3EA539D5BFA.png) |
| 上月：0 值短空态 | [截图](screenshots/ios/final/335A4617-D45E-430D-A624-B6A717B7B7E6.png) |

## 限制与既有诊断

Focus / Overview Kotlin→Swift 回调已显式转入 MainActor；fixture 和联合 Task/Habit 写入时仍能看到 `Publishing changes from background threads` 警告。具体发布链路尚未可靠定位，保持待追踪，不断言属于既有模块或已消除，也未扩改无关状态存储模块。

未验收实机后台到点通知送达、iOS 26.0 accessory 兼容分支、iPad、旧系统 fallback、深色/超大字体。通知排程权限/错误反馈和 ISO 时间解析已实现、单测覆盖，但这不等于实机后台提醒已验收。模拟器定向通过不代表正式全项目测试通过。
