package com.deadliner.domain.habit

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

@Serializable
data class HabitSchedule(
    val kind: HabitScheduleKind,
    val startOn: String,
    val endOn: String? = null,
    val weekdaysMask: Int? = null,
    val algorithmKey: String? = null,
) {
    init {
        require(endOn == null || endOn >= startOn) { "Habit schedule endOn must not precede startOn" }
        when (kind) {
            HabitScheduleKind.WEEKLY_DAYS -> require((weekdaysMask ?: 0) in 1..HabitWeekdays.ALL) {
                "WEEKLY_DAYS requires a non-empty weekday mask"
            }
            HabitScheduleKind.ALGORITHM -> require(!algorithmKey.isNullOrBlank()) {
                "ALGORITHM requires algorithmKey"
            }
            HabitScheduleKind.ONCE -> Unit
        }
    }
}

@Serializable
enum class HabitScheduleKind {
    WEEKLY_DAYS,
    ONCE,
    ALGORITHM,
}

object HabitWeekdays {
    const val MONDAY = 1 shl 0
    const val TUESDAY = 1 shl 1
    const val WEDNESDAY = 1 shl 2
    const val THURSDAY = 1 shl 3
    const val FRIDAY = 1 shl 4
    const val SATURDAY = 1 shl 5
    const val SUNDAY = 1 shl 6
    const val WEEKDAYS = MONDAY or TUESDAY or WEDNESDAY or THURSDAY or FRIDAY
    const val WEEKENDS = SATURDAY or SUNDAY
    const val ALL = WEEKDAYS or WEEKENDS

    fun contains(mask: Int, dayOfWeek: DayOfWeek): Boolean = mask and (1 shl dayOfWeek.ordinal) != 0
}

object HabitAlgorithms {
    const val EBBINGHAUS_CLASSIC_V1 = "EBBINGHAUS_CLASSIC_V1"
    val ebbinghausClassicOffsets: List<Int> = listOf(0, 1, 2, 4, 7, 15, 30, 60)
}

@Serializable
enum class HabitDueReason {
    DUE,
    BEFORE_START,
    AFTER_END,
    WEEKDAY_NOT_SELECTED,
    ALGORITHM_NOT_DUE,
    UNKNOWN_ALGORITHM,
}

@Serializable
data class HabitDueState(
    val date: String,
    val isDue: Boolean,
    val reason: HabitDueReason,
    val nextDueOn: String? = null,
)

object HabitScheduleEngine {
    fun state(schedule: HabitSchedule, date: LocalDate): HabitDueState {
        val start = LocalDate.parse(schedule.startOn)
        val end = schedule.endOn?.let(LocalDate::parse)
        if (date < start) return HabitDueState(date.toString(), false, HabitDueReason.BEFORE_START, nextDue(schedule, date))
        if (end != null && date > end) return HabitDueState(date.toString(), false, HabitDueReason.AFTER_END)

        val isDue = isDue(schedule, date)
        val reason = when {
            isDue -> HabitDueReason.DUE
            schedule.kind == HabitScheduleKind.WEEKLY_DAYS -> HabitDueReason.WEEKDAY_NOT_SELECTED
            schedule.kind == HabitScheduleKind.ALGORITHM && schedule.algorithmKey != HabitAlgorithms.EBBINGHAUS_CLASSIC_V1 -> HabitDueReason.UNKNOWN_ALGORITHM
            else -> HabitDueReason.ALGORITHM_NOT_DUE
        }
        return HabitDueState(date.toString(), isDue, reason, if (isDue) date.toString() else nextDue(schedule, date))
    }

    fun dueDates(schedule: HabitSchedule, from: LocalDate, through: LocalDate): List<LocalDate> {
        if (through < from) return emptyList()
        val result = mutableListOf<LocalDate>()
        var cursor = from
        while (cursor <= through) {
            if (isWithinBounds(schedule, cursor) && isDue(schedule, cursor)) result += cursor
            cursor = cursor.plus(DatePeriod(days = 1))
        }
        return result
    }

    fun nextDue(schedule: HabitSchedule, after: LocalDate): String? {
        val start = LocalDate.parse(schedule.startOn)
        val searchStart = if (after < start) start else after.plus(DatePeriod(days = 1))
        val hardEnd = schedule.endOn?.let(LocalDate::parse) ?: searchStart.plus(DatePeriod(days = 370))
        return when (schedule.kind) {
            HabitScheduleKind.ALGORITHM -> algorithmDates(schedule).firstOrNull { it >= searchStart && it <= hardEnd }?.toString()
            else -> dueDates(schedule, searchStart, hardEnd).firstOrNull()?.toString()
        }
    }

    private fun algorithmDates(schedule: HabitSchedule): List<LocalDate> {
        if (schedule.algorithmKey != HabitAlgorithms.EBBINGHAUS_CLASSIC_V1) return emptyList()
        val start = LocalDate.parse(schedule.startOn)
        val end = schedule.endOn?.let(LocalDate::parse)
        return HabitAlgorithms.ebbinghausClassicOffsets
            .map { start.plus(DatePeriod(days = it)) }
            .filter { end == null || it <= end }
    }

    private fun isDue(schedule: HabitSchedule, date: LocalDate): Boolean = when (schedule.kind) {
        HabitScheduleKind.WEEKLY_DAYS -> HabitWeekdays.contains(schedule.weekdaysMask ?: 0, date.dayOfWeek)
        HabitScheduleKind.ONCE -> date == LocalDate.parse(schedule.startOn)
        HabitScheduleKind.ALGORITHM -> algorithmDates(schedule).contains(date)
    }

    private fun isWithinBounds(schedule: HabitSchedule, date: LocalDate): Boolean {
        val start = LocalDate.parse(schedule.startOn)
        val end = schedule.endOn?.let(LocalDate::parse)
        return date >= start && (end == null || date <= end)
    }
}
