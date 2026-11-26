package com.deadliner.domain.statistics

import com.deadliner.domain.focus.FocusTargetKind
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.focus.FocusSession
import com.deadliner.domain.task.Task
import com.deadliner.domain.category.Category
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Pure shared aggregation. Timestamps are normalized once for all three tab projections. */
class FocusStatisticsEngine(
    sessions: List<FocusSession>,
    tasks: List<Task>,
    categories: List<Category>,
    private val timeZone: TimeZone,
    habits: List<Habit> = emptyList(),
) {
    private val habitByUid = habits.filterNot { it.isDeleted }.associateBy { it.uid }
    private val taskByUid = tasks.filterNot(Task::isDeleted).associateBy(Task::uid)
    private val categoryByUid = categories.filterNot(Category::isDeleted).associateBy(Category::uid)
    private val rows = sessions.filterNot(FocusSession::isDeleted).map { session ->
        val startedAt = Instant.parse(session.startedAt)
        FocusRow(session, startedAt, startedAt.toLocalDateTime(timeZone).date)
    }

    fun snapshot(query: FocusStatisticsQuery): FocusStatisticsSnapshot {
        val start = query.range?.startDate?.let(LocalDate::parse)
        val end = query.range?.endDateExclusive?.let(LocalDate::parse)
        val selected = rows.filter { row ->
            (start == null || row.date >= start) && (end == null || row.date < end) &&
                (query.taskUid == null || row.session.boundTarget.kind == FocusTargetKind.TASK && row.session.taskUid == query.taskUid) &&
                (query.target == null || row.session.boundTarget == query.target) &&
                (query.targetKind == null || row.session.boundTarget.kind == query.targetKind) &&
                (query.categoryUid == null || categoryUid(row) == query.categoryUid)
        }
        val days = selected.groupBy(FocusRow::date)
        val daily = if (start != null && end != null) {
            dates(start, end).map { date ->
                point(date, date.plus(DatePeriod(days = 1)), days[date].orEmpty())
            }
        } else emptyList()
        val taskDistribution = selected.filter { it.session.boundTarget.kind == FocusTargetKind.TASK }.groupBy { it.session.taskUid }.map { (uid, group) ->
            val latest = group.maxWith(compareBy<FocusRow> { it.startedAt }.thenBy { it.session.uid })
            FocusTaskStatistic(
                taskUid = uid,
                taskTitle = taskByUid[uid]?.title ?: latest.session.taskTitleSnapshot,
                categoryUid = categoryUid(latest),
                durationSeconds = group.sumOf { it.session.durationSeconds },
                sessionCount = group.size,
                isTaskAvailable = uid in taskByUid,
            )
        }.sortedWith(compareByDescending<FocusTaskStatistic> { it.durationSeconds }.thenBy { it.taskUid })
        val categoryDistribution = selected.groupBy(::categoryUid).map { (uid, group) ->
            FocusCategoryStatistic(
                categoryUid = uid,
                categoryTitle = uid?.let { categoryByUid[it]?.name },
                durationSeconds = group.sumOf { it.session.durationSeconds },
                sessionCount = group.size,
            )
        }.sortedWith(compareByDescending<FocusCategoryStatistic> { it.durationSeconds }.thenBy { it.categoryUid.orEmpty() })
        val targetDistribution = selected.groupBy { it.session.boundTarget }.map { (target, group) ->
            val latest = group.maxWith(compareBy<FocusRow> { it.startedAt }.thenBy { it.session.uid })
            val title = if (target.kind == FocusTargetKind.TASK) taskByUid[target.uid]?.title else habitByUid[target.uid]?.name
            FocusTargetStatistic(target, title ?: latest.session.targetTitleSnapshot, categoryUid(latest),
                group.sumOf { it.session.durationSeconds }, group.size, title != null)
        }.sortedWith(compareByDescending<FocusTargetStatistic> { it.durationSeconds }.thenBy { it.target.kind.name }.thenBy { it.target.uid })
        return FocusStatisticsSnapshot(query, timeZone.id, summary(selected), daily, taskDistribution, categoryDistribution, targetDistribution)
    }

    fun overview(today: LocalDate): FocusOverviewStatistics {
        val tomorrow = today.plus(DatePeriod(days = 1))
        val weekStart = today.minus(DatePeriod(days = today.dayOfWeek.ordinal))
        val monthStart = LocalDate(today.year, today.month, 1)
        val previousMonthStart = monthStart.minus(DatePeriod(months = 1))
        val monthBeforePrevious = previousMonthStart.minus(DatePeriod(months = 1))
        val lastMonth = snapshot(range(previousMonthStart, monthStart))
        val previousSummary = summary(inRange(monthBeforePrevious, previousMonthStart))
        val change = lastMonth.summary.totalDurationSeconds - previousSummary.totalDurationSeconds
        val daily = dates(today.minus(DatePeriod(days = 6)), tomorrow).map { day ->
            point(day, day.plus(DatePeriod(days = 1)), inRange(day, day.plus(DatePeriod(days = 1))))
        }
        val weekly = (3 downTo 0).map { offset ->
            val start = weekStart.minus(DatePeriod(days = offset * 7))
            val end = start.plus(DatePeriod(days = 7))
            point(start, end, inRange(start, minOf(end, tomorrow)))
        }
        val monthly = (11 downTo 0).map { offset ->
            val start = monthStart.minus(DatePeriod(months = offset))
            val end = start.plus(DatePeriod(months = 1))
            point(start, end, inRange(start, minOf(end, tomorrow)))
        }
        val rollingWeekly = (3 downTo 0).map { offset ->
            val end = tomorrow.minus(DatePeriod(days = offset * 7))
            val start = end.minus(DatePeriod(days = 7))
            point(start, end, inRange(start, end))
        }
        return FocusOverviewStatistics(
            asOfDate = today.toString(),
            timeZoneId = timeZone.id,
            today = summary(inRange(today, tomorrow)),
            thisWeek = summary(inRange(weekStart, tomorrow)),
            thisMonth = snapshot(range(monthStart, tomorrow)),
            allTime = summary(rows.filter { it.date < tomorrow }),
            daily = daily,
            weekly = weekly,
            monthly = monthly,
            lastMonth = FocusMonthlyReview(
                current = lastMonth,
                previousSummary = previousSummary,
                durationChangeSeconds = change,
                durationChangePercent = previousSummary.totalDurationSeconds.takeIf { it > 0 }?.let {
                    change.toDouble() / it * 100.0
                },
            ),
            rollingWeekly = rollingWeekly,
            recentDaysSummary = summary(inRange(today.minus(DatePeriod(days = 6)), tomorrow)),
            recentMonthsSummary = summary(inRange(monthStart.minus(DatePeriod(months = 11)), tomorrow)),
            recentWeeksSummary = summary(inRange(tomorrow.minus(DatePeriod(days = 28)), tomorrow)),
        )
    }

    private fun categoryUid(row: FocusRow): String? =
        (if (row.session.boundTarget.kind == FocusTargetKind.TASK) taskByUid[row.session.taskUid]?.categoryUid
        else habitByUid[row.session.boundTarget.uid]?.categoryUid)?.takeIf { it in categoryByUid }

    private fun summary(rows: List<FocusRow>): FocusStatisticsSummary {
        val seconds = rows.sumOf { it.session.durationSeconds }
        return FocusStatisticsSummary(
            totalDurationSeconds = seconds,
            sessionCount = rows.size,
            taskCount = rows.filter { it.session.boundTarget.kind == FocusTargetKind.TASK }.map { it.session.boundTarget }.distinct().size,
            habitCount = rows.filter { it.session.boundTarget.kind == FocusTargetKind.HABIT }.map { it.session.boundTarget }.distinct().size,
            targetCount = rows.map { it.session.boundTarget }.distinct().size,
            activeDayCount = rows.map(FocusRow::date).distinct().size,
            averageSessionDurationSeconds = if (rows.isEmpty()) 0 else seconds / rows.size,
            durationByTarget = breakdown(rows),
        )
    }

    private fun inRange(start: LocalDate, end: LocalDate) = rows.filter { it.date >= start && it.date < end }

    private fun point(start: LocalDate, end: LocalDate, rows: List<FocusRow>) =
        FocusDurationPoint(start.toString(), end.toString(), rows.sumOf { it.session.durationSeconds }, rows.size, breakdown(rows))

    private fun breakdown(rows: List<FocusRow>) = FocusDurationBreakdown(
        taskDurationSeconds = rows.filter { it.session.boundTarget.kind == FocusTargetKind.TASK }.sumOf { it.session.durationSeconds },
        habitDurationSeconds = rows.filter { it.session.boundTarget.kind == FocusTargetKind.HABIT }.sumOf { it.session.durationSeconds },
    )

    private fun range(start: LocalDate, end: LocalDate) =
        FocusStatisticsQuery(FocusStatisticsDateRange(start.toString(), end.toString()))

    private fun dates(start: LocalDate, end: LocalDate): List<LocalDate> =
        generateSequence(start) { it.plus(DatePeriod(days = 1)) }.takeWhile { it < end }.toList()

    private data class FocusRow(val session: FocusSession, val startedAt: Instant, val date: LocalDate)
}
