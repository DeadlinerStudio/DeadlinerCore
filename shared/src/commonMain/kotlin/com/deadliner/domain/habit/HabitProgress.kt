package com.deadliner.domain.habit

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

@Serializable
data class HabitProgress(
    val habitUid: String,
    val window: HabitGoalWindow,
    val windowStart: String,
    val windowEnd: String,
    val completedCount: Int,
    val targetCount: Int,
    val isComplete: Boolean,
    val fraction: Double,
)

@Serializable
data class HabitDayOverview(
    val date: String,
    val dueHabitCount: Int,
    val completedHabitCount: Int,
    val completionFraction: Double,
    val completedByHabitUid: Map<String, Boolean>,
)

object HabitProgressEngine {
    fun progress(habit: Habit, records: List<HabitRecord>, on: LocalDate): HabitProgress {
        val range = goalRange(habit, on)
        val completed = records.asSequence()
            .filter { it.habitUid == habit.uid && !it.isDeleted && it.status == HabitRecordStatus.COMPLETED }
            .filter { it.occurredOn in range.start.toString()..range.endInclusive.toString() }
            .sumOf(HabitRecord::count)
        val target = habit.goal.targetCount
        return HabitProgress(
            habitUid = habit.uid,
            window = habit.goal.window,
            windowStart = range.start.toString(),
            windowEnd = range.endInclusive.toString(),
            completedCount = completed,
            targetCount = target,
            isComplete = completed >= target,
            fraction = (completed.toDouble() / target).coerceIn(0.0, 1.0),
        )
    }

    fun dayOverview(habits: List<Habit>, records: List<HabitRecord>, on: LocalDate): HabitDayOverview {
        val dailyDue = habits.filter {
            !it.isDeleted && it.status == HabitStatus.ACTIVE &&
                it.goal.window == HabitGoalWindow.SCHEDULED_DAY &&
                HabitScheduleEngine.state(it.schedule, on).isDue
        }
        val completed = dailyDue.associate { it.uid to progress(it, records, on).isComplete }
        val completedCount = completed.values.count { it }
        return HabitDayOverview(
            date = on.toString(),
            dueHabitCount = dailyDue.size,
            completedHabitCount = completedCount,
            completionFraction = if (dailyDue.isEmpty()) 0.0 else completedCount.toDouble() / dailyDue.size,
            completedByHabitUid = completed,
        )
    }

    fun goalRange(habit: Habit, on: LocalDate): ClosedRange<LocalDate> = when (habit.goal.window) {
        HabitGoalWindow.SCHEDULED_DAY -> on..on
        HabitGoalWindow.WEEK -> {
            val start = on.minus(DatePeriod(days = on.dayOfWeek.ordinal))
            start..start.plus(DatePeriod(days = 6))
        }
        HabitGoalWindow.MONTH -> {
            val start = LocalDate(on.year, on.month, 1)
            start..start.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
        }
        HabitGoalWindow.LIFETIME -> LocalDate.parse(habit.schedule.startOn)..on
    }
}
