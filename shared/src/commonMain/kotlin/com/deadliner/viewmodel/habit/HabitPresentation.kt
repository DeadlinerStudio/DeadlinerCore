package com.deadliner.viewmodel.habit

import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitDueReason
import com.deadliner.domain.habit.HabitProgressEngine
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitScheduleEngine
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitTrackingMode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/** Shared presentation projection used by lists, search, widgets, and native bridges. */
object HabitPresentationEngine {
    fun statuses(
        habits: List<Habit>,
        records: List<HabitRecord>,
        rawDate: String,
        today: LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
    ): List<HabitDayStatus> {
        val date = LocalDate.parse(rawDate)
        return habits.map { habit ->
            val due = HabitScheduleEngine.state(habit.schedule, date)
            val progress = HabitProgressEngine.progress(habit, records, date)
            HabitDayStatus(
                habit = habit,
                doneCount = progress.completedCount,
                targetCount = progress.targetCount,
                isCompleted = progress.isComplete,
                canApplyPrimaryAction = due.isDue && !progress.isComplete && habit.status == HabitStatus.ACTIVE && date <= today,
                primaryAction = HabitPrimaryAction(
                    kind = if (habit.tracking.mode == HabitTrackingMode.QUANTITY) {
                        HabitPrimaryActionKind.ADD_QUANTITY
                    } else {
                        HabitPrimaryActionKind.CHECK_OFF
                    },
                    amount = habit.tracking.defaultIncrement,
                ),
                scheduleState = HabitScheduleUiState(
                    isDue = due.isDue,
                    reason = due.reason,
                    hintKind = when (due.reason) {
                        HabitDueReason.BEFORE_START -> HabitScheduleHintKind.STARTS_IN
                        HabitDueReason.ALGORITHM_NOT_DUE -> HabitScheduleHintKind.NEXT_REVIEW_IN
                        HabitDueReason.AFTER_END -> HabitScheduleHintKind.COMPLETED_CYCLE
                        else -> null
                    },
                    nextDueOn = due.nextDueOn,
                    daysUntil = due.nextDueOn?.let { date.daysUntil(LocalDate.parse(it)) },
                ),
            )
        }
    }
}

@Serializable
data class HabitDayStatus(
    val habit: Habit,
    val doneCount: Int,
    val targetCount: Int,
    val isCompleted: Boolean,
    val canApplyPrimaryAction: Boolean,
    val primaryAction: HabitPrimaryAction,
    val scheduleState: HabitScheduleUiState,
)

@Serializable
data class HabitPrimaryAction(
    val kind: HabitPrimaryActionKind,
    val amount: Int,
)

@Serializable
enum class HabitPrimaryActionKind { CHECK_OFF, ADD_QUANTITY }

@Serializable
data class HabitScheduleUiState(
    val isDue: Boolean,
    val reason: HabitDueReason,
    val hintKind: HabitScheduleHintKind? = null,
    val nextDueOn: String? = null,
    val daysUntil: Int? = null,
)

@Serializable
enum class HabitScheduleHintKind { STARTS_IN, NEXT_REVIEW_IN, COMPLETED_CYCLE }
