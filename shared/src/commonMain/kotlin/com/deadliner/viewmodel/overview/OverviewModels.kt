package com.deadliner.viewmodel.overview

import com.deadliner.domain.habit.Habit
import com.deadliner.domain.task.Task

/**
 * Kept as a compatibility projection for existing Android and HarmonyOS callers.
 * New clients should render [OverviewSummary] and the detailed metric collections.
 */
data class HabitProgress(
    val habit: Habit,
    val completedToday: Int,
    val targetToday: Int,
    val progress: Float,
)

/**
 * Kept as a compatibility projection for existing Android and HarmonyOS callers.
 */
data class OverviewStats(
    val activeTasks: Int = 0,
    val completedTasks: Int = 0,
    val archivedTasks: Int = 0,
    val totalTasks: Int = 0,
    val activeHabits: Int = 0,
    val completedHabitsToday: Int = 0,
    val habitProgress: List<HabitProgress> = emptyList(),
)

/** Platform-neutral state consumed by the Overview screens on all clients. */
data class OverviewUiState(
    val stats: OverviewStats = OverviewStats(),
    val summary: OverviewSummary = OverviewSummary(),
    val history: OverviewHistory = OverviewHistory(),
    val completionBuckets: List<OverviewCompletionBucket> = OverviewCompletionBucket.defaultValues(),
    val daily: List<OverviewDailyStat> = emptyList(),
    val monthly: List<OverviewMonthlyStat> = emptyList(),
    val weekly: List<OverviewWeeklyStat> = emptyList(),
    val contribution: List<OverviewContributionDay> = emptyList(),
    val lastMonth: OverviewLastMonth = OverviewLastMonth(),
    val upcomingTasks: List<Task> = emptyList(),
    val activeHabits: List<Habit> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

data class OverviewSummary(
    val completedToday: Int = 0,
    val pendingDueFromNow: Int = 0,
    val overdueToday: Int = 0,
    val activeAbandoned: Int = 0,
)

data class OverviewHistory(
    val completed: Int = 0,
    val pending: Int = 0,
    val abandoned: Int = 0,
    val overdue: Int = 0,
)

enum class OverviewCompletionPeriod {
    OVERNIGHT,
    MORNING,
    AFTERNOON,
    EVENING,
}

/**
 * Do not use `OverviewCompletionPeriod.entries` on Apple targets. Kotlin/Native
 * 2.1 can dereference a null enum-entry cache here in optimized frameworks.
 */
internal fun overviewCompletionPeriods(): List<OverviewCompletionPeriod> = listOf(
    OverviewCompletionPeriod.OVERNIGHT,
    OverviewCompletionPeriod.MORNING,
    OverviewCompletionPeriod.AFTERNOON,
    OverviewCompletionPeriod.EVENING,
)

data class OverviewCompletionBucket(
    val period: OverviewCompletionPeriod,
    val completedCount: Int,
) {
    companion object {
        fun defaultValues() = overviewCompletionPeriods().map { OverviewCompletionBucket(it, 0) }
    }
}

/** `date` is an ISO-8601 local date (yyyy-MM-dd), never a localized label. */
data class OverviewDailyStat(
    val date: String,
    val completedCount: Int,
    val overdueCount: Int,
)

/** `monthStart` is an ISO-8601 local date for the first day of the month. */
data class OverviewMonthlyStat(
    val monthStart: String,
    val totalCount: Int,
    val completedCount: Int,
    val overdueCompletedCount: Int,
)

data class OverviewWeeklyStat(
    val weekStart: String,
    val completedCount: Int,
)

data class OverviewContributionDay(
    val date: String,
    val completedCount: Int,
)

data class OverviewLastMonth(
    val monthStart: String? = null,
    val daily: List<OverviewDailyStat> = emptyList(),
    val metrics: OverviewMonthlyMetrics = OverviewMonthlyMetrics(),
    val completedTaskTitles: List<String> = emptyList(),
)

data class OverviewMonthlyMetrics(
    val totalTasks: Int = 0,
    val completedTasks: Int = 0,
    val completionRate: Double = 0.0,
    val overdueTasks: Int = 0,
    val mostActivePeriod: OverviewCompletionPeriod? = null,
    val mostActivePeriodCompletedCount: Int = 0,
    val averageCompletionMinutes: Int? = null,
    val previousTotalTasks: Int = 0,
    val previousCompletedTasks: Int = 0,
    val previousCompletionRate: Double = 0.0,
    val previousOverdueTasks: Int = 0,
)
