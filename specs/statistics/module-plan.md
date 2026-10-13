# 专注时间统计 Module Plan

## 模块拆分

| Layer | Location | Responsibility |
| --- | --- | --- |
| Contract/domain | `domain/statistics` | Focus 查询、摘要、日/周/月序列、任务/分类分布、三页签投影与纯聚合 |
| Local implementation | `db/repository/statistics` | SQLDelight 一致性读取、Task/Category 元数据、变更订阅 |
| Presentation adapter | Android `ui/overview` | 生命周期加载、时长本地化、摘要/图表/分布卡片 |
| Composition | `DeadlinerDatabase` | 暴露 `focusStatistics` |

`OverviewViewModel` remains a compatibility projection for existing platform screens. It is not changed or replaced by this module.

## 平台映射

```text
Android 三页签 -> FocusOverviewState -> FocusStatisticsRepository
                                      ^
DeadlinerDatabase -> LocalFocusStatisticsRepository -> FocusStatisticsEngine
```

Android 独立 OverviewActivity 与 ModernHost 共享 OverviewContent，统一在此加载专注投影。
iOS `Features/Overview`、HarmonyOS `pages/routes/overview` 后续接桥接；本轮不更改两端 UI。

## 文件拆分策略

合同、纯聚合、SQL 适配各独立文件；Android 状态加载独立。FocusStatisticsCards 提供内嵌摘要/状态/分布，FocusDurationCharts 使用项目 compose-charts，FocusStatisticsPresenter 负责单位与标签适配。原总览/趋势/月报卡片提供内嵌插槽，不再前置整套独立专注卡片；每个日/月/周双图卡为稳定列表项。

保留 Core 的 weekly 自然周合同，新增 rollingWeekly 投影与既有 Overview 的连续 7 日周图对齐，避免破坏已有合同或让同一张卡两个图的日期范围不同。

极简修订不增加新的页面层级：总览的专注内容采用单行摘要，趋势移除重复 KPI 和副标题，月报沿用原指标网格。复用 titleMedium / bodyMedium / bodySmall 的统一层级、左右对齐及既有卡片间距；图表坐标标签和图例使用共享 labelSmall 参数。分布仍是短排行列表，而非自绘图形。

## 风险点

分类是当前分类视图；上月比较不使用当月未结束数据。专注数据不传入既有 AI/分享数据路径。
查询订阅覆盖会话、任务与分类，生命周期恢复触发首次查询。Core Flow 本身不轮询日期；Android 接入补丁在可见期间每 30 秒检查本地日期变化，跨午夜后重新订阅。
本地 AAR 没有依赖 POM，Android 必须显式声明与 Core 一致版本的 SQLDelight `coroutines-extensions`；已通过模拟器发现并补齐此运行时依赖。
既有独立统计 Activity 的任务数据仍为进入时读取的快照；本轮专注订阅不改写该机制，设备测试在创建任务 fixture 后重建 Activity 再验证双指标。内嵌/宽屏已编译但未分别运行验收。

## 习惯概览增量模块

- `FocusStatisticsModels / Engine`：可缺省的 FocusDurationBreakdown、targetKind 筛选、三个趋势期间摘要；仍只读取有效 FocusSession。
- `LocalFocusStatisticsRepository`：保留原四表订阅，验证 Habit 当前元数据更新、时间更正和删除后刷新。
- Android `FocusStatisticsCards / Presenter / DurationCharts`：现有插槽增加短类型摘要与弹出详情；TrendAnalysisScreen / DashboardScreen 传递 KMP 期间摘要，沿用宿主、主题和图表 API。
- 纯规则、SQLite Flow、Android 展示层与三页签设备测试分别验证。新增内容保持独立、小于 1000 行，无持久化或同步破坏性变更。
