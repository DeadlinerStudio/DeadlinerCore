# 专注时间统计 Tasks

## 阶段 7：习惯专注直观接入概览（2026-10-12）

- [x] 确认已有合计/排行包含 Habit，冻结类型拆分短摘要、不加页签/额外图的增量方案。
- [x] KMP 类型筛选、摘要/点时长拆分与趋势期间摘要。
- [x] Habit 元数据/会话变化订阅及 Habit-only/混合/日期/删除回归。
- [x] Android 原三个页签与点详情展示类型时长，保持旧任务成果口径。
- [x] Core、Android 编译和三页签深浅色设备验收，回写证据。

### 阶段 7 验收记录

- 新增 FocusDurationBreakdown，summary / point 的 durationByTarget 可缺省为 null；fresh Core 为空期也提供明确的零值拆分，旧投影不猜类型。
- targetKind 查询支持全部 Habit 时间；近 7 天、近 12 月、连续 4 周的摘要由 KMP 输出，与原序列同范围，不在 Android 重建期间规则。
- 总览今日/累计、趋势各期间与上月指标复用原卡片插槽，仅有习惯投入时增加一行短类型摘要。原专注图仍为合计，点详情及无障碍描述可读类型拆分；不增加图/Tab/图表库，不把 Habit 计入任务成果。
- Core 原生定向 30 项通过，其中新增 4 项类型规则/序列/跨日/旧投影测试及 1 项真实 SQLite Habit 元数据/会话订阅测试。Android 310 项单元测试零失败，应用及测试 APK 构建成功；iOS Arm64 / OHOS Arm64 Kotlin 编译通过，原生 UI 不属于本轮。
- 技能文件规模检查脚本未提供，按更严格总行数检查：Core 统计引擎 163、模型 127、仓库订阅测试 121、Android 新摘要组件 29、改动 Dashboard 787，均小于 1000。规格校验、两仓库 diff 检查通过。
- 设备验收使用独立 Pixel_9_Pro 只读离线模拟器 emulator-5556，没有操作已有 emulator-5588、清空用户应用数据或推送代码。测试只软删除自己的独立命名夹具。初次上月同文摘要断言产生节点歧义，仅修正测试定位后复测。
- 最终 AndroidJUnitRunner 浅色标准字体 3 项、深色 1.3 倍字体 3 项通过，覆盖混合数据三个页签、纯习惯组件与空态。日志分别为 `/private/tmp/habit-overview-ui-light.log` 和 `/private/tmp/habit-overview-ui-dark-large.log`。截图各 10 张，保存于 `screenshots/habit-overview-light/` 和 `screenshots/habit-overview-dark-large/`；已视觉检查无遮挡的浅色总览、深色总览/趋势/上月指标，类型摘要在大字体下自然换行，无固定高度截断。
- 初轮截图出现模拟器 SystemUI ANR 系统遮挡，未计为视觉通过；仅关闭本轮独立设备的系统 ANR 对话框后复跑并重导全部截图，最终证据无该遮挡。未清除应用数据。
- 沿用项目 compose-charts 0.1.7；已检查本地对应版本源码的 TextMeasurer / drawText 弹窗布局支持多行文案。弹窗实际点按、真正 TalkBack、中文界面、RTL、宽屏及其他端原生 UI 未在本轮做运行验收，不扩大通过声明。

## 阶段 5：融合 UI 重构（2026-10-09）

- [x] 冻结双指标默认可见、无二级 Tab/下拉、沿用 compose-charts 的方案。
- [x] 总览今日/历史摘要内嵌，投入分布后置且紧凑。
- [x] 趋势原日/月/周卡同时显示任务/专注两图，日期范围对齐。
- [x] 上月融合指标网格与活动卡，环比不按好坏着色。
- [x] 移除手画专注图，验证数据更新/空态/独立纵轴。
- [x] Android 构建、设备深浅色及大字体截图、Core 对齐回归和规格回写。

以下记录属于初版独立卡片，不能代表重构布局已验收。

## 阶段 6：极简展示修订

- [x] 用户否定融合版的文字密度，冻结短标题、统一排版、摘要单行和去重复说明的方向。
- [x] 精简今日/历史专注摘要，减少趋势副标题与双图高度。
- [x] 月报短环比、紧凑次级值，去除无意义的重复分类列表。
- [x] 重新构建并做深色/浅色大字体截图验收。

## 当前版本验收记录：极简融合版（2026-10-09）

- 总览使用统一左对齐 titleMedium 标题，专注压成单行；历史与今日任务栏统一为上数值、下标签的等宽排版，移除固定 80dp 和滚动标签。
- 趋势每个原有时段卡只保留一行标题/完成数、原任务图例、任务图，以及一行专注时长/分钟单位和专注图；不再重复大号 KPI、成果副标题或额外说明。任务图高 160/180/160dp，专注图高 120dp。
- 上月两项专注指标接入原网格且成对等高；环比为中性色短百分比，次数/均值合并为次级信息。单一未分类不再重复任务分布。
- 图表继续使用项目 compose-charts 0.1.7 和共享主题参数；全零专注只显示短空态。没有新增导航或指标切换。
- Core 聚合测试 6 项、真实 SQLite 订阅测试 1 项通过；原计时服务 9 项及 ViewModel 2 项回归通过。新增 rollingWeekly 测试保证两个周图同为连续 7 日窗口，不改变既有自然周合同。
- Android 最终 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest --tests com.aritxonly.deadliner.ui.overview.FocusStatisticsPresenterTest --offline` 成功，展示层测试 1 项通过。
- 最终 APK 在 Pixel 9 / API 36.1 只读模拟器通过 AndroidJUnitRunner：英文深色 2 项、中文浅色字体 1.3 倍 2 项。检查三页签、同时可见的任务/专注图、真实会话的标题更新与时长更正；确定性空态用同一组件的隔离测试布局验证，不代表另一个正式页面。
- 实际截图分别保存于 `screenshots/minimal-dark/` 和 `screenshots/minimal-light-large/`，各 9 张。已视觉检查总览、趋势、上月指标/活动及分布；全部使用临时测试数据。`fusion-*` 是测试文件命名，不意味着旧融合截图仍为最终版本。
- 所有本轮新增/改动的展示文件小于 1000 总行；Dashboard 最大 787 总行。技能的大文件检查脚本不可用，因此以 `wc -l` 的更严格总行上限作替代检查。两仓库 diff 检查和规格校验通过。

本节记录实现与技术验收，不代替用户对视觉设计的认可；早期独立卡片和 `fusion-dark/`、`fusion-light-large/` 截图只作迭代归档。

## 阶段 1：规格冻结

- [x] 按用户修正专注时间范围，映射「总览 / 趋势 / 上月」。
- [x] 明确有效会话、日期归属、当前分类、前月为零及空态。

## 阶段 2：基础设施

- [x] 替换未发布综合统计草稿，建立 Focus 查询/聚合/三页签合同。
- [x] 实现数据库一致性读取与会话/任务/分类变更订阅。

## 阶段 3：功能实现

- [x] Android 两个入口和宽屏布局统一接入共享 OverviewContent。
- [x] 总览、趋势、上月新增专注卡片，保留已有任务统计。

## 阶段 4：验证与回写

- [x] Core 日期/删除/元数据/分类/筛选/月份比较测试。
- [x] Android Kotlin 编译和 APK 构建。
- [x] 设备或模拟器验证三页签，明确无法验证的状态。
- [x] Core 与 Android 文件规模、diff 和规格校验并回写。

## 初版验证归档（2026-10-09，不代表当前布局）

- `:shared:testDebugUnitTest --tests com.deadliner.domain.statistics.FocusStatisticsEngineTest --offline`：5 项通过。
- `:shared:macosArm64Test --tests com.deadliner.db.FocusStatisticsRepositoryTest`：1 项真实 SQLite 测试通过，持续订阅验证结束、更正、删除会话，任务改名/删除，分类改名/删除。
- 同次原有 `FocusTimerIntegrationTest` 9 项和 `FocusTimerViewModelTest` 2 项通过，合计 11 项计时回归。
- `validate_feature_spec.py specs/statistics`：通过；`git diff --check`：通过。
- 新 Core Kotlin 文件实际总行数分别 133 / 97 / 11 / 48；新增测试 130 以下，因此有效代码行数也小于 1000，无大文件扩张风险。
- Android `:app:assembleDebug :app:assembleDebugAndroidTest --offline` 通过，包括最新 Core AAR 构建、部署与校验。
- Pixel 9 / API 36.1 只读离线模拟器：直接 AndroidJUnitRunner 运行 `FocusStatisticsInstrumentedTest`，2 项通过；验证原三个页签、真实完成会话、任务改名与时长更正的 UI 订阅、确定性的空态和无虚假增长。
- 已检查深色三页签截图，包括完整 12 月趋势及上月每日图。发现并修复上月两位日期标签截断；日/周/月及上月图表拆成独立列表项，方便定位并避免超大项。
- 浅色 + 系统字体 1.3：同样 2 项设备测试通过，检查摘要换行、月趋势和日期标签截图。测试用稳定的主列表标签搜索尚未组合的卡片，避免内部滚动节点歧义。
- Android 新状态/卡片/测试文件分别 52 / 258 / 156 总行；改动后的 Dashboard 最大 777 总行，均不超过 1000 有效代码行。两仓库 `git diff --check` 通过。

截图证据：`screenshots/focus-overview.png`、`focus-trend-monthly.png`、`focus-last-month-daily.png` 为深色验收；`screenshots/light-large/` 为浅色大字体验收。截图包含仅在临时模拟器内创建的测试任务与历史会话，不是用户真实数据。

## 平台接入与验证边界

用户追加写入授权后，已将实现应用到 Android 仓库 `/Users/aritxonly/Codes/ProjectDeadliner/Deadliner`。独立 Activity、ModernHost 与宽屏共用同一个 OverviewContent 订阅，不更改原导航/玻璃 chrome、已有 Task 统计、AI 月报与分享数据。

`android-integration.applypatch` 仅保留最初接入草稿归档，不代表最终源码，不应重复应用。最终实现以 Android `ui/overview`、OverviewActivity 和 build.gradle.kts 为准；实际代码另外修复运行时依赖和日期/列表展示问题。

- 已修复的运行时问题：本地 AAR 不传递 SQLDelight Flow 依赖；Android 显式补齐与 Core 相同版本的 `coroutines-extensions` 后设备复验通过。
- `connectedDebugAndroidTest --offline` 受缺失的 UTP 插件缓存阻塞；应用和测试 APK 可正常离线构建，实际设备验收改用 `adb shell am instrument` 成功运行，不宣称 Gradle UTP 任务通过。
- 模拟器预存旧数据触发已有 V5 子任务布尔值迁移错误，发生在统计页启动之前。本轮只清理 `-read-only` 临时副本中明确的应用包数据，原 AVD 数据保留，未修改迁移逻辑；升级迁移仍需单独修复/验收。
- 设备验收为独立统计 Activity 的紧凑布局；内嵌/宽屏共享调用已编译，但未单独做运行截图验收。iOS/HarmonyOS 尚未接入 UI；RTL、真正跨午夜及真实同步服务未做设备验收。
