# 原生专注 UI 验收

日期：2026-10-12。实现已落地；iOS模拟器回归通过，鸿蒙编译与签名通过。鸿蒙真机视觉/手势尚未验收，不能据此宣称三端视觉完全一致。

后续用户实测发现的鸿蒙重组/规范/miniBar问题已纳入 [focus-harmony-repair](../focus-harmony-repair/spec.md)。本文件记录上一轮结果；当前鸿蒙关闭、材质作用域和miniBar策略以修正规格为准，不能用本记录替代真机验收。

## 已落地合同

- 新建专注只由任务/习惯列表右滑露出的专注按钮打开，并预绑定对应目标。删除iOS首页顶栏专注入口、两端任务详情启动卡。
- 专注页顶栏仅标题；返回、放弃、数量确认/取消在内容区。鸿蒙Sheet关闭图标也关闭，保留原生拖动手柄。
- 设置页：绑定目标、正/倒计时分段选择、时间预览、15/25/45/60分钟、折叠自定义1～180分钟、单个开始按钮。
- 计时页：居中目标、圆盘大数字、状态、结束/暂停继续/联合完成三圆形按钮。摘要、最多五条历史置后；结束后显示结果，不重新堆叠启动表单。
- iOS26圆形操作使用原生 `glassEffect`，旧版保留安全回退；鸿蒙使用原生Ring进度组件和项目 `MaterialPresets` / `SheetCompat` 系统材质兼容层。鸿蒙低于系统材质支持版本使用既有回退。
- 现有活动会话全局栏、通知和目标变更冲突返回当前计时仍保留；它们不是独立新建入口。未调整原有 `tabViewBottomAccessory` / HdsTabs MiniBar合同。

## 验证结果

| 项目 | 结果 | 范围与证据 |
| --- | --- | --- |
| iOS实际应用源码编译 | 通过 | 新设置、计时、列表与详情源码均编译、链接、签名成功 |
| iOS专注单测 | 4通过 | 时长/数量校验、Core时间精度、通知路由单次消费、隔离fixture准备 |
| iOS UI回归 | 2通过，0失败 | 真列表右滑入口；Task正计时、暂停、退出与重启恢复、联合完成；数量Habit输入12后联合打卡；二元Habit倒计时提前结束；历史更正/删除；既有概览三页签 |
| iOS视觉检查 | 已执行 | 检查任务/习惯右滑、设置页、倒计时预设、计时盘、数量确认截图：未见顶栏动作、主操作裁切或排版重叠 |
| 鸿蒙完整HAP | 通过 | `CompileArkTS`、打包及 `SignHap` 成功；首次编译三个局部错误修复后复编通过 |
| 鸿蒙宿主合同 | 18通过 | Repository/metadata实际方法配合mock原生接口；MiniBar部分为源码断言，非真机渲染 |
| 新UI入口合同 | 13通过 | `check-contract.mjs`，源码断言而非设备行为测试 |
| 规格/差异/规模 | 通过（本次范围） | spec校验、两端 `git diff --check`；新增Focus组件134/60/53行、宿主243/514行；本次其它生产文件原始总行数均≤808，因此有效行数未超1000 |
| 鸿蒙真机 | 未执行 | 有权限的 `hdc list targets` 返回 `[Empty]` |

鸿蒙构建使用显式 `DEVECO_HOME`、DevEco内置JBR与现有签名环境；现有前置脚本调用Core部署时指定 `--skip-ios --skip-android`。没有执行Android修改、构建或部署，也没有修改Core业务源码。

## iOS验收隔离与限制

运行于专用 `DeadlinerFocusValidation`（iPhone17 Pro / iOS26.2），不清理用户数据库；仅准备命名fixture。测试后该设备已处于Shutdown。

沿用上一轮的 `/private/tmp/DeadlinerFocusValidation.xcodeproj`：引用真实仓库源码，复用已部署Shared；不重复SyncKMP前置部署，排除既有 `ToolCallExecutorBatchCompatTests.swift` 的旧AI接口编译阻塞。没有修改正式工程以掩盖这些既有错误，因此本记录不是正式全量测试通过证明。

测试仍出现既有 `Publishing changes from background threads` 警告，本轮未解决或认定其来源。尚未验收iOS旧版本、深色/大字体、鸿蒙真机系统材质及API23/24 MiniBar视觉；这轮没有修改这些兼容结构。

## 复现与产物

```sh
node specs/focus-native-ui/check-contract.mjs /Users/aritxonly/Codes/iOS/Deadliner /Users/aritxonly/Codes/HarmonyOS/Deadliner
python3 /Users/aritxonly/.codex/skills/deadliner-spec-dev/scripts/validate_feature_spec.py specs/focus-native-ui
```

- iOS日志：`/private/tmp/focus-ui-ios-unit.log`、`/private/tmp/focus-ui-ios-ui.log`。
- iOS UI结果：`/private/tmp/deadliner-focus-native-derived/Logs/Test/Test-Deadliner-2026.10.12_15-31-48-+0800.xcresult`。
- 鸿蒙复编日志：`/private/tmp/focus-ui-harmony-rebuild.log`，结果 `BUILD SUCCESSFUL in 3 min 4 s`。
- 签名HAP：`/Users/aritxonly/Codes/HarmonyOS/Deadliner/entry/build/default/outputs/default/entry-default-signed.hap`。
- 全部16张UI测试截图及名称映射：[manifest.json](screenshots/ios/manifest.json)。

## 关键截图

- [任务右滑入口](screenshots/ios/E4465C9F-7F2E-4852-98B1-D0AB85C00DCF.png)
- [习惯右滑入口](screenshots/ios/A9F2BB7F-B59F-43A8-B2F7-A6AFC8BACDC1.png)
- [正计时设置](screenshots/ios/03018262-AE5C-4EC1-9076-6EBB24E4316A.png)
- [倒计时设置](screenshots/ios/4CD53982-C796-4979-B83C-EC166D6382F0.png)
- [计时圆盘与三操作](screenshots/ios/74870C24-CE30-46F6-A096-9DAC611E24BC.png)
- [暂停后全局栏](screenshots/ios/CD1D4B3F-89AC-4EED-A9CB-84913C097C16.png)
- [数量确认](screenshots/ios/C00B7A5D-2052-494E-980C-ACF5974D60C0.png)
- [结束结果与历史](screenshots/ios/F1186968-3F7E-4E9B-B9A1-FA2BEBC6F191.png)

## 后续修复：iOS任务全滑完成

2026-10-12。仅调整iOS任务卡生产代码：恢复 `allowsFullSwipe: !selectionMode`，活动任务的按钮顺序改为完成、专注、放弃。专注不再占首项，只通过悬停点击打开；已完成/已放弃分支的撤销、恢复和归档不变，Habit右滑规则不变。无Android、鸿蒙或Core业务修改。

依据：[SwiftUI swipeActions](https://developer.apple.com/documentation/swiftui/view/swipeactions(edge:allowsfullswipe:content:))的全滑执行首项规则。源码入口合同增加顺序/全滑断言，当前14项通过；旧Task专注UI测试改用部分右滑，不能用全滑打开专注。

沿用上述隔离工程与 `DeadlinerFocusValidation`，编译真实仓库源码、4项专注单测通过。两个UI用例分别通过：

- 既有 `testTaskHabitAndAccessoryFlows`：Task部分右滑入口、计时/暂停/重启恢复/联合完成，数量与二元Habit联动通过。
- 新 `testTaskSwipeOrderAndFullSwipeCompletion`：验证完成/专注/放弃的屏幕位置、点击专注入口、完整右滑后活动行消失且完成态出现、不误开专注。最终1项执行、0失败。

首次用默认 `swipeRight(.slow)` 模拟全滑，实际只拖开约190像素，后续定位/完成断言失败；记录保留，没有将它算作通过。最终改为固定屏幕坐标从行左侧拖到屏幕右侧，直接等待活动态消失并重新查询完成态，回归通过。不是通过修改生产逻辑迎合测试。

- 最终编译/单测日志：`/private/tmp/focus-ios-swipe-unit-span.log`。
- 既有流程通过记录：`/private/tmp/focus-ios-swipe-ui-final.log`（同批旧版全滑用例失败，不能视为整批通过）。
- 最终全滑通过日志：`/private/tmp/focus-ios-swipe-ui-span.log`。
- 最终结果：`/private/tmp/deadliner-focus-native-derived/Logs/Test/Test-Deadliner-2026.10.12_18-07-37-+0800.xcresult`。
- 已人工查看通过截图：[完成/专注/放弃顺序](screenshots/ios-swipe/B197D96D-AAC8-47CD-B428-AC7C75FD24FA.png)、[全滑后完成态](screenshots/ios-swipe/55BEB642-665A-4F33-B291-2274647ABEFA.png)。

仍是隔离工程验证，不代表含旧AI测试的正式全量测试通过。既有背景线程发布警告未在本次修复；未做真机或多选手势实测，多选禁用逻辑通过源码检查。
