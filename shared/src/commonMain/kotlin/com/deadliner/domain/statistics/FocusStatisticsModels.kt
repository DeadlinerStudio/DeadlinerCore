package com.deadliner.domain.statistics

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import com.deadliner.domain.focus.FocusTargetKind

/** ISO local dates, inclusive start and exclusive end. */
@Serializable
data class FocusStatisticsDateRange(val startDate: String, val endDateExclusive: String) {
    init {
        require(LocalDate.parse(startDate) < LocalDate.parse(endDateExclusive)) {
            "Focus statistics range must have a positive span"
        }
    }
}

/** Null range requests all-time totals/distributions without an unbounded daily series. */
@Serializable
data class FocusStatisticsQuery(
    val range: FocusStatisticsDateRange? = null,
    val taskUid: String? = null,
    val categoryUid: String? = null,
    val target: com.deadliner.domain.focus.FocusTargetRef? = null,
    val targetKind: FocusTargetKind? = null,
) {
    init {
        require(taskUid == null || taskUid.isNotBlank()) { "taskUid must not be blank" }
        require(categoryUid == null || categoryUid.isNotBlank()) { "categoryUid must not be blank" }
    }
}

/** Raw seconds, not habit progress. Null on legacy projections means no type breakdown was supplied. */
@Serializable
data class FocusDurationBreakdown(val taskDurationSeconds: Long = 0, val habitDurationSeconds: Long = 0) {
    init { require(taskDurationSeconds >= 0 && habitDurationSeconds >= 0) }
    val totalDurationSeconds: Long get() = taskDurationSeconds + habitDurationSeconds
}

@Serializable
data class FocusStatisticsSummary(
    val totalDurationSeconds: Long = 0,
    val sessionCount: Int = 0,
    val taskCount: Int = 0,
    val activeDayCount: Int = 0,
    val averageSessionDurationSeconds: Long = 0,
    val habitCount: Int = 0,
    val targetCount: Int = taskCount + habitCount,
    val durationByTarget: FocusDurationBreakdown? = null,
)

@Serializable
data class FocusDurationPoint(
    val startDate: String,
    val endDateExclusive: String,
    val durationSeconds: Long = 0,
    val sessionCount: Int = 0,
    val durationByTarget: FocusDurationBreakdown? = null,
)

@Serializable
data class FocusTaskStatistic(
    val taskUid: String,
    val taskTitle: String,
    val categoryUid: String?,
    val durationSeconds: Long,
    val sessionCount: Int,
    val isTaskAvailable: Boolean,
)

/** A null UID/title denotes unclassified time, including records for deleted tasks. */
@Serializable
data class FocusCategoryStatistic(
    val categoryUid: String?,
    val categoryTitle: String?,
    val durationSeconds: Long,
    val sessionCount: Int,
)

@Serializable
data class FocusStatisticsSnapshot(
    val query: FocusStatisticsQuery,
    val timeZoneId: String,
    val summary: FocusStatisticsSummary = FocusStatisticsSummary(),
    val daily: List<FocusDurationPoint> = emptyList(),
    val tasks: List<FocusTaskStatistic> = emptyList(),
    val categories: List<FocusCategoryStatistic> = emptyList(),
    val targets: List<FocusTargetStatistic> = emptyList(),
)

@Serializable
data class FocusTargetStatistic(
    val target: com.deadliner.domain.focus.FocusTargetRef,
    val title: String,
    val categoryUid: String?,
    val durationSeconds: Long,
    val sessionCount: Int,
    val isAvailable: Boolean,
)

@Serializable
data class FocusMonthlyReview(
    val current: FocusStatisticsSnapshot,
    val previousSummary: FocusStatisticsSummary,
    val durationChangeSeconds: Long,
    /** Null when the preceding month has no focus time. Never infinity/NaN. */
    val durationChangePercent: Double?,
)

/** Ready-to-render projections for the existing Overview / Trends / Last month tabs. */
@Serializable
data class FocusOverviewStatistics(
    val asOfDate: String,
    val timeZoneId: String,
    val today: FocusStatisticsSummary,
    val thisWeek: FocusStatisticsSummary,
    val thisMonth: FocusStatisticsSnapshot,
    val allTime: FocusStatisticsSummary,
    val daily: List<FocusDurationPoint>,
    val weekly: List<FocusDurationPoint>,
    val monthly: List<FocusDurationPoint>,
    val lastMonth: FocusMonthlyReview,
    /** Four consecutive seven-day windows ending today, aligned with the existing Task trends. */
    val rollingWeekly: List<FocusDurationPoint> = emptyList(),
    val recentDaysSummary: FocusStatisticsSummary? = null,
    val recentMonthsSummary: FocusStatisticsSummary? = null,
    val recentWeeksSummary: FocusStatisticsSummary? = null,
)
