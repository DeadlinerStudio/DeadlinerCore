# Native Focus Rollout Tasks

## 阶段 1：规格冻结

- [x] 读取用户指定 spec 技能及引用、Harmony AGENTS、ArkTS/ArkUI技能。
- [x] 两平台分工，Android 明确只读，冻结行为和原生全局栏合同。
- [x] MCP 官方文档确认 HdsTabs API23 / VERTICAL API24，iOS SDK availability 由 iOS agent 核实。
- [x] 两端详细能力差距回写（下列实现范围与验收边界）。

## 阶段 2：基础设施

- [x] typed target、目标/会话选择与 Habit 联合命令桥接补齐。
- [x] 两端专注统计投影桥接与现有页面接线。
- [x] 生命周期、后台通知、Mutation 刷新与错误映射补齐（真实后台投递另列设备验收）。

## 阶段 3：功能实现

- [x] iOS Task/Habit 入口、设置、时钟、联合结束、历史管理及原生 accessory。
- [x] 鸿蒙 Task/Habit 入口、设置、时钟、联合结束、历史管理及 MiniBar API分支。
- [x] 现有统计页专注UI与两类目标时长拆分，保持当前结构。

## 阶段 4：验证与回写

- [x] 完整批次后构建 Core 目标、iOS与鸿蒙应用（DEVECO_HOME显式）；不构建 Android。iOS 正式全套测试受旧 AI 测试阻塞，诊断工程应用构建通过，口径单列。
- [x] Core 定向回归、平台 host tests 与可用 iOS 模拟器运行；无鸿蒙设备，真机和系统兼容分支明确列为未验收。
- [x] 文件规模、两端 diff、规格验证，回写构建日志、截图、限制与实际功能状态。

## 当前状态

两端功能已实现，Core 46 项、iOS 4 项定向单测与 2 条 UI 回归、鸿蒙完整签名构建及宿主合同检查通过。本轮完成可执行验证，不宣称全系统版本、真机后台或正式 iOS 全套测试已验收。

## 已取得验证证据

- Core macOS Arm64 定向回归：31 项计时/统计/原生 facade 测试 + 15 项 Habit 联合结束/元数据/跨日/事务回滚测试，46 项全部通过。日志：`/private/tmp/native-focus-core-tests.log`、`/private/tmp/native-focus-habit-tests.log`。
- OHOS Core release 动态库链接成功，4 个专注 C 导出符号已核实；仅部署到鸿蒙目录，Android / iOS 明确跳过。日志：`/private/tmp/native-focus-core-build.log`、`/private/tmp/native-focus-ohos-deploy.log`。
- 鸿蒙核心文件规模检查：本轮新增/修改 ETS 未超过 1000 有效行；旧 SearchPage / DatabaseHelper 超阈值，不在本轮扩大治理范围。
- 鸿蒙 `hdc list targets` 无设备；API23/24 MiniBar 真实排布、后台提醒投递与通知点击暂不能记为设备验收通过。
- iOS 使用独立 `DeadlinerFocusValidation`（iOS26.2）模拟器，不操作用户已有模拟器数据。
- 鸿蒙完整 `assembleHap`、ArkTS/C++ 编译与签名通过：`/private/tmp/harmony-focus-build-java25.log`，新产物 `entry/build/default/outputs/default/entry-default-signed.hap`（37,608,318 字节）。签名失败通过正确 DevEco JDK `jbr/Contents/Home` + PATH 修复，无证书/签名配置改动。
- iOS Core release XCFramework 的 arm64 / simulator arm64 / simulator x64 均已完成。正式工程的应用 Swift 源码编译通过，正式测试目标因既有 `ToolCallExecutorBatchCompatTests.swift` 过期 AI API 调用失败，未修改该测试。
- iOS 专注定向测试采用 `/private/tmp` 临时诊断工程，引用同一正式应用源与本轮新 XCFramework，仅去除已完成的 Core 同步依赖并通过命令行隔离旧失败测试；不改仓库工程配置。应用链接/签名成功，4 项专注 Swift Testing case 通过，命令 exit0 / TEST SUCCEEDED。日志：`/private/tmp/focus-native-ios-isolated-tests.log`。
- iOS 真实 UI 已完成 Task 正计时/暂停/重启恢复/联合完成、数量习惯联合打卡12、二元习惯倒计时联合打卡1；只读落库核实 Task completed，数量 recorded_amount12，二元 recorded_amount1、planned1500 / actual6秒。早前 Menu/历史行定位失败日志保留，不当作业务通过；最终修正后 2 条 UI case / 0 failures / TEST EXECUTE SUCCEEDED，覆盖历史更正删除与概览/趋势/上月专注内容。日志：`/private/tmp/focus-native-ios-final-ui.log`。
- iOS 最后一次 Swift 源码编译/应用链接/签名与 4 项单测通过：`/private/tmp/focus-native-ios-final-unit-retry.log`。Kotlin→Swift 专注/统计回调显式 MainActor hop；联合写入仍观察到线程发布警告，保留追踪项，未扩大修改其他模块。
- iOS 已导出实际截图至 `screenshots/ios/`，最终 13 张位于 `screenshots/ios/final/`。主 agent 已目视检查暂停 accessory、数量输入、二元实际时长历史及统计三页签，未见遮挡；并不代表旧系统、大字体或真机验证通过。

## 实现范围与原始缺口

- 原 iOS / 鸿蒙 Task-only 草稿不能选择 Habit，且 native桥接/通知/统计尚未全宿主接线。本轮统一复用 Core typed target、Habit record context 与会话 UID 合同。
- iOS：共享 presentation singleton、Task/Habit 入口与选择器、计时/暂停/恢复/联合结束、历史管理、Swift Charts 专注统计；RichMain 使用原生 TabView accessory，非 TabView 宿主及旧系统使用原生安全区回退。
- 鸿蒙：独立 typed models / NAPI focus bridge / repository、Task/Habit 入口与时钟 Sheet、数量与历史管理、既有 MPChart 专注统计；根级计时宿主覆盖全部 Tab，HdsTabs MiniBar API23 水平、API24 VERTICAL，旧系统/禁用新UI保持全局回退。
- 前后台 reconcile、原生倒计时提醒与提醒失败提示、Task/Habit mutation guard、批量预检均已接线。鸿蒙应用完整构建与签名通过；iOS 应用链接/签名在诊断工程通过，正式测试目标的既有失败单独保留。
- 复审修复：iOS 依据保存事实变化刷新统计，不依赖 busy 瞬态；通知冷启动保留 UID，并拒绝已删除通知记录。鸿蒙历史结果刷新持续使用同一 UID，只读选择不发布 revision，活动会话优先；失效通知不回退成另一会话的打卡。
- 鸿蒙 host checks：9 项格式/Outcome helper + 18 项 mocked-native repository/元数据与源码合同检查通过，其中 MiniBar 的 3 项仅源码断言；不可计为设备渲染或后台验证。

## 最终验收边界与交接

- [iOS 实现与验收](ios-acceptance.md)：正式工程阻塞原因、诊断工程差异、私有模拟器范围、实际数据库与截图、线程警告及未验收列表。
- [鸿蒙实现与验收](/Users/aritxonly/Codes/HarmonyOS/Deadliner/docs/focus-native-validation.md)：签名产物、官方 MiniBar/ReminderAgent 合同、host checks 与无设备限制。
- 两端前台功能不等于实机后台提醒送达；iOS26.0/旧系统/iPad、深色/大字体，鸿蒙API23/24排布与生命周期竞态仍需相应设备验收。
- 未修改、构建或部署 Android；不改另一对话负责的总览看板。复审未发现新增高置信阻塞问题；现有用户改动原位保留。
