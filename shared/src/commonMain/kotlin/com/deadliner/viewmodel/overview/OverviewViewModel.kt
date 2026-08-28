package com.deadliner.viewmodel.overview

import com.deadliner.db.repository.HabitRepository
import com.deadliner.db.repository.TaskRepository
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitProgressEngine
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.task.Task
import com.deadliner.platform.PlatformLogger
import com.deadliner.domain.task.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The single Overview aggregation authority. All output is stable, raw data:
 * clients localize labels and render charts without re-scanning repositories.
 */
class OverviewViewModel(
    private val taskRepository: TaskRepository,
    private val habitRepository: HabitRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val nowProvider: () -> Instant = { Clock.System.now() },
    private val timeZoneProvider: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val _uiState = MutableStateFlow(OverviewUiState())
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    fun initialize() = refresh()

    fun refresh() {
        scope.launch {
            overviewLog("refresh begin")
            _uiState.update { it.copy(isLoading = true, error = null) }
            _uiState.value = loadNow()
        }
    }

    /**
     * Computes a complete state without starting a coroutine or exporting a
     * StateFlow collector. iOS uses this as a pull snapshot because Swift ↔
     * Kotlin callback collection is not reliable on all devices yet.
     */
    fun loadNow(): OverviewUiState = try {
        val now = nowProvider()
        val timeZone = timeZoneProvider()
        overviewLog("repository reads begin timezone=$timeZone")
        val tasks = taskRepository.list().filterNot(Task::isDeleted)
        overviewLog("tasks loaded count=${tasks.size}")
        val habits = habitRepository.list().filterNot(Habit::isDeleted)
        overviewLog("habits loaded count=${habits.size}")
        val habitRecords = habitRepository.allRecords().filterNot(HabitRecord::isDeleted)
        overviewLog("habit records loaded count=${habitRecords.size}; aggregation begin")
        buildState(tasks, habits, now, timeZone, habitRecords).also { state ->
            overviewLog(
                "aggregation complete daily=${state.daily.size} monthly=${state.monthly.size} weekly=${state.weekly.size} contribution=${state.contribution.size}",
            )
        }
    } catch (error: Throwable) {
        overviewLog("snapshot failed error=${error.message}")
        OverviewUiState(isLoading = false, error = error.message)
    }

    internal fun buildState(
        tasks: List<Task>,
        habits: List<Habit>,
        now: Instant,
        timeZone: TimeZone,
        habitRecords: List<HabitRecord> = emptyList(),
    ): OverviewUiState {
        val today = now.toLocalDateTime(timeZone).date
        // Timestamp parsing is comparatively expensive on Kotlin/Native. The
        // charts below reuse the same three fields hundreds of times, so parse
        // once per task and share the normalized values across all projections.
        val taskTimes = tasks.associate { task ->
            val dueAt = task.dueAt.instantOrNull()
            val completedAt = task.completedAt.instantOrNull()
            task.uid to OverviewTaskTimes(
                dueAt = dueAt,
                dueDate = dueAt?.toLocalDateTime(timeZone)?.date,
                completedAt = completedAt,
                completedDate = completedAt?.toLocalDateTime(timeZone)?.date,
                completionPeriod = completedAt?.toOverviewCompletionPeriod(timeZone),
                startAt = task.startAt.instantOrNull(),
            )
        }
        fun times(task: Task): OverviewTaskTimes = taskTimes.getValue(task.uid)
        val active = tasks.filterNot(::isArchived)
        val completed = tasks.filter(::isCompleted)
        val abandoned = tasks.filter(::isAbandoned)
        val actionable = active.filterNot { isCompleted(it) || isAbandoned(it) }

        val completedToday = active.count { task -> isCompleted(task) && times(task).completedDate == today }
        val pendingDueFromNow = actionable.count { task ->
            times(task).dueAt?.let { it >= now } == true
        }
        val overdueToday = actionable.count { task ->
            times(task).dueAt?.let { it < now && times(task).dueDate == today } == true
        }
        val overdue = actionable.count { task -> times(task).dueAt?.let { it < now } == true }

        val completionBuckets = overviewCompletionPeriods().map { period ->
            OverviewCompletionBucket(
                period = period,
                completedCount = completed.count { task -> times(task).completionPeriod == period },
            )
        }
        val daily = buildDaily(tasks, today.minus(DatePeriod(days = 6)), 7, taskTimes, now)
        val monthly = buildMonthly(tasks, today, 12, taskTimes)
        val weekly = buildWeekly(completed, today, 4, taskTimes)
        val contribution = buildContribution(completed, today, 150, taskTimes)
        val lastMonth = buildLastMonth(tasks, today, taskTimes, now)
        val upcoming = actionable
            .filter { times(it).dueAt != null }
            .sortedBy { times(it).dueAt }
            .take(5)
        val activeHabits = habits.filter { it.status == HabitStatus.ACTIVE }
        val habitProgress = activeHabits.map { habit ->
            val computed = HabitProgressEngine.progress(habit, habitRecords, today)
            HabitProgress(
                habit = habit,
                completedToday = computed.completedCount,
                targetToday = computed.targetCount,
                progress = computed.fraction.toFloat(),
            )
        }
        val habitDayOverview = HabitProgressEngine.dayOverview(activeHabits, habitRecords, today)
        val legacyStats = OverviewStats(
            activeTasks = tasks.count { it.state == TaskState.ACTIVE },
            completedTasks = completed.size,
            archivedTasks = tasks.count(::isArchived),
            totalTasks = tasks.size,
            activeHabits = activeHabits.size,
            completedHabitsToday = habitDayOverview.completedHabitCount,
            habitProgress = habitProgress,
        )

        return OverviewUiState(
            stats = legacyStats,
            summary = OverviewSummary(completedToday, pendingDueFromNow, overdueToday, abandoned.count { !isArchived(it) }),
            history = OverviewHistory(
                completed = completed.size,
                pending = tasks.count { !isCompleted(it) && !isAbandoned(it) },
                abandoned = abandoned.size,
                overdue = overdue,
            ),
            completionBuckets = completionBuckets,
            daily = daily,
            monthly = monthly,
            weekly = weekly,
            contribution = contribution,
            lastMonth = lastMonth,
            upcomingTasks = upcoming,
            activeHabits = activeHabits,
            isLoading = false,
        )
    }

    private fun buildDaily(
        tasks: List<Task>,
        rangeStart: LocalDate,
        days: Int,
        taskTimes: Map<String, OverviewTaskTimes>,
        now: Instant,
    ): List<OverviewDailyStat> {
        val completedByDate = tasks.asSequence()
            .filter(::isCompleted)
            .mapNotNull { taskTimes.getValue(it.uid).completedDate }
            .groupingBy { it }
            .eachCount()
        val overdueByDate = tasks.asSequence()
            .filterNot(::isCompleted)
            .mapNotNull { task ->
                taskTimes.getValue(task.uid).takeIf { it.dueAt?.let { due -> due < now } == true }?.dueDate
            }
            .groupingBy { it }
            .eachCount()
        return (0 until days).map { offset ->
            val day = rangeStart.plus(DatePeriod(days = offset))
            OverviewDailyStat(
                date = day.toString(),
                completedCount = completedByDate[day] ?: 0,
                overdueCount = overdueByDate[day] ?: 0,
            )
        }
    }

    private fun buildMonthly(
        tasks: List<Task>,
        today: LocalDate,
        months: Int,
        taskTimes: Map<String, OverviewTaskTimes>,
    ): List<OverviewMonthlyStat> {
        val currentMonth = LocalDate(today.year, today.month, 1)
        return (months - 1 downTo 0).map { offset ->
            val monthStart = currentMonth.minus(DatePeriod(months = offset))
            val nextMonth = monthStart.plus(DatePeriod(months = 1))
            val inMonth = tasks.filter { taskTimes.getValue(it.uid).dueDate?.let { date -> date >= monthStart && date < nextMonth } == true }
            OverviewMonthlyStat(
                monthStart = monthStart.toString(),
                totalCount = inMonth.size,
                completedCount = inMonth.count(::isCompleted),
                overdueCompletedCount = inMonth.count { task ->
                    isCompleted(task) && taskTimes.getValue(task.uid).let { times ->
                        times.dueAt?.let { due -> times.completedAt?.let { it > due } == true } == true
                    }
                },
            )
        }
    }

    private fun buildWeekly(
        completed: List<Task>,
        today: LocalDate,
        weeks: Int,
        taskTimes: Map<String, OverviewTaskTimes>,
    ): List<OverviewWeeklyStat> =
        (weeks - 1 downTo 0).map { offset ->
            val end = today.minus(DatePeriod(days = offset * 7))
            val start = end.minus(DatePeriod(days = 6))
            OverviewWeeklyStat(
                weekStart = start.toString(),
                completedCount = completed.count { taskTimes.getValue(it.uid).completedDate?.let { date -> date >= start && date <= end } == true },
            )
        }

    private fun buildContribution(
        completed: List<Task>,
        today: LocalDate,
        days: Int,
        taskTimes: Map<String, OverviewTaskTimes>,
    ): List<OverviewContributionDay> {
        val start = today.minus(DatePeriod(days = days - 1))
        val completedByDate = completed.asSequence()
            .mapNotNull { taskTimes.getValue(it.uid).completedDate }
            .groupingBy { it }
            .eachCount()
        return (0 until days).map { offset ->
            val day = start.plus(DatePeriod(days = offset))
            OverviewContributionDay(day.toString(), completedByDate[day] ?: 0)
        }
    }

    private fun buildLastMonth(
        tasks: List<Task>,
        today: LocalDate,
        taskTimes: Map<String, OverviewTaskTimes>,
        now: Instant,
    ): OverviewLastMonth {
        val thisMonth = LocalDate(today.year, today.month, 1)
        val monthStart = thisMonth.minus(DatePeriod(months = 1))
        val previousMonthStart = monthStart.minus(DatePeriod(months = 1))
        val lastMonthTasks = tasks.filter { taskTimes.getValue(it.uid).dueDate?.let { date -> date >= monthStart && date < thisMonth } == true }
        val previousTasks = tasks.filter { taskTimes.getValue(it.uid).dueDate?.let { date -> date >= previousMonthStart && date < monthStart } == true }
        val days = generateSequence(monthStart) { day -> day.plus(DatePeriod(days = 1)).takeIf { it < thisMonth } }.count()
        val daily = buildDaily(tasks, monthStart, days, taskTimes, now)
        val metrics = buildMetrics(lastMonthTasks, previousTasks, taskTimes)
        return OverviewLastMonth(
            monthStart = monthStart.toString(),
            daily = daily,
            metrics = metrics,
            completedTaskTitles = lastMonthTasks.filter(::isCompleted).map(Task::title),
        )
    }

    private fun buildMetrics(
        current: List<Task>,
        previous: List<Task>,
        taskTimes: Map<String, OverviewTaskTimes>,
    ): OverviewMonthlyMetrics {
        fun completedRate(tasks: List<Task>): Double = tasks.takeIf { it.isNotEmpty() }
            ?.let { set -> set.count(::isCompleted).toDouble() / set.size * 100.0 } ?: 0.0
        fun overdue(tasks: List<Task>) = tasks.count { !isCompleted(it) }
        val completed = current.filter(::isCompleted)
        val buckets = completed.groupingBy { taskTimes.getValue(it.uid).completionPeriod }.eachCount()
        val active = buckets.maxByOrNull { it.value }
        val durations = completed.mapNotNull { task ->
            val start = taskTimes.getValue(task.uid).startAt ?: return@mapNotNull null
            val finish = taskTimes.getValue(task.uid).completedAt ?: return@mapNotNull null
            (finish - start).inWholeMinutes.takeIf { it >= 0 }
        }
        return OverviewMonthlyMetrics(
            totalTasks = current.size,
            completedTasks = completed.size,
            completionRate = completedRate(current),
            overdueTasks = overdue(current),
            mostActivePeriod = active?.key,
            mostActivePeriodCompletedCount = active?.value ?: 0,
            averageCompletionMinutes = durations.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            previousTotalTasks = previous.size,
            previousCompletedTasks = previous.count(::isCompleted),
            previousCompletionRate = completedRate(previous),
            previousOverdueTasks = overdue(previous),
        )
    }

    private fun isArchived(task: Task): Boolean = task.state == TaskState.ARCHIVED || task.state == TaskState.ABANDONED_ARCHIVED
    private fun isAbandoned(task: Task): Boolean = task.state == TaskState.ABANDONED || task.state == TaskState.ABANDONED_ARCHIVED
    private fun isCompleted(task: Task): Boolean = task.state == TaskState.COMPLETED || task.state == TaskState.ARCHIVED
}

private data class OverviewTaskTimes(
    val dueAt: Instant?,
    val dueDate: LocalDate?,
    val completedAt: Instant?,
    val completedDate: LocalDate?,
    val completionPeriod: OverviewCompletionPeriod?,
    val startAt: Instant?,
)

/**
 * Parses timestamp shapes written by both legacy SwiftData and KMP without
 * throwing. Overview iterates every task many times, so using `runCatching`
 * for old malformed values creates thousands of Throwable instances and can
 * destabilize Kotlin/Native's GC on iOS.
 *
 * Supported values are canonical KMP UTC timestamps (`...Z`) and the legacy
 * local forms `yyyy-MM-dd`, `yyyy-MM-ddTHH:mm[:ss[.fraction]]`, and
 * `yyyy-MM-dd HH:mm[:ss[.fraction]]`. Unknown values are safely ignored.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
private fun String?.instantOrNull(): Instant? {
    val value = this?.trim().orEmpty()
    if (value.isEmpty()) return null

    val isUtc = value.endsWith('Z') || value.endsWith('z')
    val localDateTime = parseOverviewLocalDateTime(if (isUtc) value.dropLast(1) else value) ?: return null
    val parsed = localDateTime.toInstant(if (isUtc) TimeZone.UTC else TimeZone.currentSystemDefault())
    return Instant.fromEpochMilliseconds(parsed.toEpochMilliseconds())
}

private fun parseOverviewLocalDateTime(value: String): LocalDateTime? {
    val separatorIndex = value.indexOfFirst { it == 'T' || it == ' ' }
    val datePart = if (separatorIndex >= 0) value.substring(0, separatorIndex) else value
    val timePart = if (separatorIndex >= 0) value.substring(separatorIndex + 1) else null

    if (datePart.length != 10 || datePart[4] != '-' || datePart[7] != '-') return null
    val year = datePart.substring(0, 4).toIntOrNull() ?: return null
    val month = datePart.substring(5, 7).toIntOrNull() ?: return null
    val day = datePart.substring(8, 10).toIntOrNull() ?: return null
    if (year !in 1..9999 || month !in 1..12 || day !in 1..daysInMonth(year, month)) return null

    if (timePart == null) return LocalDateTime(year, month, day, 0, 0)
    val clock = timePart.substringBefore('.')
    val pieces = clock.split(':')
    if (pieces.size !in 2..3 || pieces.any { it.isEmpty() }) return null
    val hour = pieces[0].toIntOrNull() ?: return null
    val minute = pieces[1].toIntOrNull() ?: return null
    val second = pieces.getOrNull(2)?.toIntOrNull() ?: 0
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null

    return LocalDateTime(year, month, day, hour, minute, second)
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private fun Instant.toOverviewCompletionPeriod(timeZone: TimeZone): OverviewCompletionPeriod = toLocalDateTime(timeZone).hour.let { hour ->
    when (hour) {
        in 0..5 -> OverviewCompletionPeriod.OVERNIGHT
        in 6..11 -> OverviewCompletionPeriod.MORNING
        in 12..17 -> OverviewCompletionPeriod.AFTERNOON
        else -> OverviewCompletionPeriod.EVENING
    }
}

private fun overviewLog(message: String) {
    PlatformLogger().d("overview", message)
}
