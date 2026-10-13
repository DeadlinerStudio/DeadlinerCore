# iOS / HarmonyOS 专注全量对齐规格

日期：2026-10-12。状态：两端已实现；构建与可用模拟器定向验收通过，真机/兼容分支未验收项见 tasks.md 与平台验收文档。用户授权两端并行实现，Android 明确只读。

## 背景

Core / Android 已支持 Task、Habit 绑定专注、联合结束和习惯专注统计；iOS / HarmonyOS 留有 Task-only 草稿及宿主接入缺口。此次补齐原生客户端，不实施另一对话负责的总览看板。

## 目标

- 与当前 Android / Core 实际行为对齐，而非早期 Task-only PRD。规则来源以 `specs/habit-focus/prd.md`、`specs/statistics/` 和现行 Core 为准。
- 必须选择有效 Task 或 Habit；支持正计时、1～180 分钟倒计时、暂停/恢复、实际时长结束、丢弃确认、结果与历史更正/软删除。
- Task「结束并完成」原子结束与完成；Habit「结束并打卡」使用开始日和 Core 幂等合同，数量型填写本次实际数量，取消不改变计时或打卡。
- 补齐各端任务/习惯入口、当前目标与全局选择、详情时间/历史、前后台恢复、通知与现有统计页专注呈现。
- iOS 使用 tabViewBottomAccessory（SDK/系统 availability 确认后分支）；鸿蒙使用 HdsTabs MiniBar，API 24+ VERTICAL，API 23 维持水平，只有活动计时才出现。

## 非目标

- 不修改 Android 源码、资源、构建产物、AAR 或部署；不运行 Android 自动同步构建。
- 不实现总览看板、第五个 Tab、自由计时、番茄循环、白噪音或活动计时跨端同步。
- 不重写领域计时/统计，不变更数据库 schema、同步/备份格式。
- 不用平台轮询自行持久化 elapsed、替代联合事务或推算习惯完成数量。

## 用户场景

1. 从任务或习惯开始绑定专注；已有活动会话时明确解决旧会话，不能静默切换目标。
2. 跨主页面通过原生全局栏查看/暂停/恢复并返回时钟；暂停态仍显示，结束后移除。
3. 提前结束倒计时只记录实际有效时间；到点仅保存专注、不自动完成任务或打卡。
4. 数量型习惯联合结束填写实际数量；跨午夜明确归属启动日；输入期间到点只保存一次，结果补打卡保持幂等。
5. 在详情或历史更正会话、删除错误记录，统计响应更新；删除目标保留历史快照。
6. 前后台/杀进程后基于 Core 持久化锚点恢复，倒计时通知拒绝权限不阻止计时。

## 行为约束

- 本设备单会话，活动仅本机；已保存会话复用现有同步/备份。Task 非 ACTIVE 不可继续；Habit ACTIVE、非计划日可计时但不能联合打卡，已达目标仍可专注。
- Task 完成/归档/放弃/删除，Habit 归档/删除遇到当前会话必须明确解决；远端失效通过 reconcile 收尾，不能静默丢失已投入时间。
- 会话更正遵守 Core 的 1 秒至 min(24 小时,真实跨度) 校验，不改目标或原始始末时间；删除不撤销 Task 完成或 Habit 打卡。
- 统计只计算已保存、未删除会话；按 startedAt 本地日归属，Task/Habit 时间合计与拆分、期间汇总保持 Core 口径，Task 成果数不混 Habit。
- 数量/单位/计划元数据变化重新校验；旧会话 UID 联合命令不可作用于新活动计时。

## 原生 UI 与兼容

- iOS：SwiftUI 原生列表/Sheet/按钮，iOS 26+ TabView accessory；更低部署系统沿用安全的原生 safeAreaInset 回退，不无保护调用新 API。禁用态、长名称、大字体和 inline/expanded placement 适配。
- HarmonyOS：既有 ArkUI/HDS 风格与导航；HdsTabs `.barOverlap(true)`、`.barPosition(BarPosition.End)`、`.vertical(false)`，通过 barFloatingStyle.miniBar 的 miniBarBuilder 构建内容。
- 官方 MCP 已核实 HdsTabsMiniBar 起始 API 23，barLayoutMode / HdsBarLayoutMode 起始 API 24。API 23 不引用新枚举分支；SDK 缺失 API24 时不得假造类型或强转绕过，应定位正确 SDK/包或明确验证限制。
- 不新增页面 chrome、指标子 Tab 或重复计时器；新功能文件控制在 1000 有效行内，旧大文件仅必要接线并拆职责。

## 验收标准

- 两端均可完成 Task / Habit 正计时和倒计时完整流程、暂停恢复、联合结束、数量输入、更正与删除；计时行为由 Core 决定。
- 全局栏无会话不渲染，活动/暂停出现，点击打开当前会话，操作不可误触或打开旧会话。
- 鸿蒙 API 23 保持单行、API24+双行；iOS accessory 版本分支编译通过，兼容部署版本不崩溃。
- 应用构建、Core 定向回归与可用设备/模拟器运行证据分别记录，不把编译等同于真机或后台到点已验收。
- 两端统计包含 Habit 时长，保持原三页签/结构，不改 Android 或总览看板文档与实现。
- 路由、深浅色、空态、错误、长文本/大字体、生命周期与通知尽可能运行验收；受限项明确记为未验收。

## 风险与约束

多仓库已有用户未提交改动，必须原位保留，不做 reset/checkout。此次采用平台桥接和 UI 增量，不计划 breaking change；若实际需破坏性合同变更，先遵循技能保护点约束并协调，不擅自升级协议。Harmony 构建显式设置 DEVECO_HOME。Android 只读；共享变更限于桥接或兼容增量，不改变 Android 行为合同。

## API 参考

- [Apple TabViewBottomAccessoryPlacement](https://developer.apple.com/documentation/swiftui/tabviewbottomaccessoryplacement)（本地 SwiftUI SDK 签名配合核实）。
- [HdsTabs 官方参考](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/ui-design-hdstabs)（通过用户指定 Harmony developer knowledge MCP 检索）。

