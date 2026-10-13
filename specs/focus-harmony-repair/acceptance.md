# 鸿蒙专注修复验收

2026-10-12。本轮仅鸿蒙生产代码和相关规格/检查脚本；没有修改iOS、Android或Core业务规则。使用用户指定的项目 `deadliner-spec-dev` 技能：先规格、拆动态Component、完整批次后编译并回写范围。

## 修复内容与依据

- Task/Habit/全局专注Sheet恢复原生 `showClose: true`，不新增启动或计时顶栏按钮。根Scroll和承载Column明确顶部对齐。
- 模式选择使用官方 `TabSegmentButtonV2` 与 `$selectedIndex` 回调，V1宿主装饰器保持不变。其它按钮显式Circle/Capsule和圆角。
- 活动、结果、摘要、目标选择、数量确认、历史拆为Component。每次查询/命令替换扁平展示快照，活动区通过标量Prop更新；暂停/联合完成回调读取当前timer，而非旧Builder参数。
- 核心圆形按钮由项目 `ImmersiveMaterialScope` 承载；必需材质插槽内再放独立 `FocusScopedRoundButton`，图标/标签/可用态用Link同步，不依赖插槽重新求值。
- miniBar取消VERTICAL和硬编码EXPAND，使用API23默认水平布局。折叠为专注图标；用户点击后展开为单行目标、时间、图标暂停/继续。展开后点目标/时间才打开Sheet，暂停按钮不会顺带打开Sheet。
- HdsTabs与内容共享 `FocusMiniBarExpanded` StorageLink，Tab重建不重置用户状态。布局/APP_TRIGGER回调不改用户展开选择，不触发Sheet；插槽/样式回调使用显式函数，不使用ArkTS不支持的Function.bind。
- miniBar采用支持主题/材质反色的系统字体资源；Task/Habit右滑图标及主按钮组件独立订阅项目 `currentTheme`，使用 `bgPrimary/accent`，不再固定白色或依赖Builder更新颜色。
- 页面与miniBar增加请求序号：晚返回的旧查询不覆盖新的命令结果，忙碌时不启动额外刷新。

这里明确移除了快照Builder和无条件展开两项代码风险；尚无真机重组/光感证据，不把宿主测试当作实测问题全部消失的证明。

官方接口依据：[SegmentButtonV2](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/ohos-arkui-advanced-segmentbuttonv2)、[HdsTabs MiniBar](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/ui-design-hdstabs)、[@Link父子同步](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/arkts-link)、[材质反色资源说明](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/arkts-immersive-light-sense-faq)。通过Harmony开发知识MCP读取。

## 验证记录

| 检查 | 结果 | 边界 |
| --- | --- | --- |
| 新修复合同 | 34通过 | 17项执行状态投影/miniBar策略/实际Sheet与miniBar方法，17项源码断言；不是ArkUI运行时重组测试 |
| 既有宿主合同 | 18通过 | Repository/metadata方法执行+MiniBar源码断言；旧VERTICAL断言已更新 |
| 原生入口合同 | 13通过 | 保留右滑入口、去详情入口等；鸿蒙关闭断言按最新需求修正；iOS仅只读检查 |
| 规模/差异/规格 | 通过（本次范围） | Focus模块无超1000有效行，其它本次文件原始总行≤804；鸿蒙 `git diff --check` 与规格校验通过 |
| 完整签名HAP | 通过 | 最终主题订阅修复批次在DevEco自带Node24下CompileArkTS、打包、SignHap通过，`BUILD SUCCESSFUL in 8 min 30 s`；首次全局Node25的工具兼容错误已绕开，无构建配置修改 |
| 真机 | 未执行 | 有权限的 `hdc list targets` 返回 `[Empty]` |

全仓库大文件扫描仍报未在本轮修改的 `SearchPage.ets`（1103有效行）与 `DatabaseHelper.ets`（1084有效行）；不扩展范围重构它们。

## 复现与日志

```sh
node specs/focus-harmony-repair/check-contract.mjs /Users/aritxonly/Codes/HarmonyOS/Deadliner
node /Users/aritxonly/Codes/HarmonyOS/Deadliner/scripts/check_focus_native_contract.mjs
node specs/focus-native-ui/check-contract.mjs /Users/aritxonly/Codes/iOS/Deadliner /Users/aritxonly/Codes/HarmonyOS/Deadliner
```

构建显式设置 `DEVECO_HOME`、`NODE_HOME`（DevEco内置Node24）、内置JBR和Core的JDK21；现有前置脚本保留 `--skip-ios --skip-android`。

- 首次工具环境错误：`/private/tmp/focus-harmony-repair-build.log`。
- 首次成功复编：`/private/tmp/focus-harmony-repair-rebuild.log`；显式回调修复复编：`/private/tmp/focus-harmony-repair-final-build.log`。
- 最终主题订阅修复编译：`/private/tmp/focus-harmony-repair-theme-build.log`。
- 检查日志：`/private/tmp/focus-harmony-repair-checks.log`、`/private/tmp/focus-harmony-repair-entry-checks.log`。
- 大文件基线：`/private/tmp/focus-harmony-repair-size.log`。
- 签名产物：[entry-default-signed.hap](/Users/aritxonly/Codes/HarmonyOS/Deadliner/entry/build/default/outputs/default/entry-default-signed.hap)。未安装到用户设备。

构建仍含已有SDK废弃接口、依赖sourceMaps及Core生成C代码告警；HdsTabs既有API23分支使用系统版本判断保护调用，兼容SDK20仍报可用性提示。新增miniBar回调的Function.bind告警已消除。构建通过不表示全仓库告警已清零，本轮没有将其扩展为全仓库重构。

## 待真机验收

1. Task/Habit右滑打开专注，原生关闭按钮可达且只关闭页面。
2. 正/倒计时开始后暂停立即显示“已暂停”、继续图标和静止时钟；继续恢复走时，结束显示结果。
3. 数量输入、取消、联合打卡、历史更正/删除均可更新。
4. 连续切换各Tab不自动展开miniBar、不打开Sheet；主动点图标展开，点内容回到计时，点暂停不打开页面。
5. 深/浅色、目标长标题、API23与26的系统材质回退、按钮光感和点击区域无裁切或串点。
