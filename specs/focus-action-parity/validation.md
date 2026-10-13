# 验证记录

范围：F01/F02/F04/F05/F06/F07。不包含 Android 概览看板和全量历史。

## 已通过

- KMP macosArm64 专注相关测试首次 59 项通过，包含新增 6 项 SQLite 集成用例；最终隔离复跑专注闭环 6 项与习惯列表 6 项均通过（后者含新增刷新用例）。整批复跑的原生运行时异常见下方，不声明最终整批稳定通过。
- Android assembleDebug + testDebugUnitTest：326 项测试通过；最终 AAR 已同步并经字节校验。
- 鸿蒙完整 assembleHap（含签名）成功；JavaCommandBuilder 调用 PATH 中的 java，验收命令同时指定 JDK 21 的 PATH/JAVA_HOME，未修改签名配置。
- 鸿蒙原生已有宿主合同：18 项通过；新合同执行取消、确认、固定 sessionUid、并发请求、习惯归档和四类警告。滑动顺序和结果桥接为源码合同，不替代 ArkUI 真机测试。
- 三端 diff --check、规格验证通过。修改文件没有新增 1000 有效行超限；鸿蒙 SearchPage/DatabaseHelper 和三方库超限为原有基线，未修改。
- iOS 最终产物原生单元测试 5 项、UI 回归 3 项全部通过：任务确认/取消/结果补完成、习惯归档/删除、右滑动作顺序及右滑到底完成。

## iOS 回归说明

使用既有专用模拟器 DeadlinerFocusValidation。只创建/处理本功能测试夹具，不清除模拟器数据库。

生产源码通过私有验证项目构建。原项目无关 ToolCallExecutorBatchCompatTests 存在旧接口编译错误，本次隔离该文件；不声明全仓 iOS 测试通过。

新增 UI 回归覆盖：原生弹出框取消且计时保留、结束仅保存后的补完成、已完成反馈、结束计时并完成原任务。

测试定位修正：iOS 弹出框使用 PopoverDismissRegion 取消；结果容器显式使用 children: .contain，避免覆盖子按钮可访问性标识。

习惯归档 UI 回归发现真实缓存遗漏：Home 原刷新只调用 selectDate 重算缓存。已补桥接 DB 重读（loadNow），保留选中日期/搜索，并新增数据库外部归档后的刷新集成测试。

## Core 整批复跑异常

以下保留此前失败证据。2026-10-13 已定位并修复 CPF 编译器异常路径未恢复 ShadowStack 根帧和 KGP 报告并发问题；新增主动 GC 回归后未过滤完整 233 项连续三轮通过，同进程十轮 2330 项通过。当前结论见 [Native 稳定性验证](../native-test-stability/validation.md)，不再以隔离通过作为最终验收。

最终追加习惯列表用例后的整批复跑，在既有 `HabitFocusIntegrationTest.startConflictsWithTaskAndV5SyncUpgradesWithoutLosingTaskHistory` 发生原生进程 SIGSEGV；堆栈位于 SQLiter Lock.unlock / Objective-C 引用转换 / 同步快照读取。第二次复跑卡在该用例的 JSON 迁移序列化与 Native GC 暂停；已采样并终止明确属于本次的测试进程。尚未归因，不修改无关同步或运行时配置来规避。

本次闭环/刷新 12 项隔离通过。发生异常的旧同步用例单独运行也通过（`focus-parity-core-sync-isolated.log`）。完整长序列原生测试稳定性保留排查，不因单例通过而宣称整批通过。失败日志 `focus-parity-core-combined-tests.log`、`focus-parity-core-combined-retry.log`；采样 `/private/tmp/focus-core-test-sample.txt`。

## 尚未验收

- 当前未连接 Android/鸿蒙设备，保留原生滑动触感、弹窗与深浅主题的设备验收。
- iOS 仅专用模拟器验收，不等价于真机、全尺寸与所有系统版本覆盖。

## 构建证据

本机日志：/private/tmp/focus-parity-core-final-tests.log、focus-parity-core-new-tests.log、focus-parity-ios-refresh-core-build.log、focus-parity-android-final-build.log、focus-parity-harmony-final-build.log、focus-parity-ios-refresh-final-tests.log。
