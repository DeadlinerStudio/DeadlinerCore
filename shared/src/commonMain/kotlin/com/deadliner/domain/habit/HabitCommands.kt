package com.deadliner.domain.habit

import kotlinx.datetime.LocalDate

sealed interface HabitRecordCommand {
    val habitUid: String
    val occurredOn: String

    data class CheckOff(override val habitUid: String, override val occurredOn: String) : HabitRecordCommand
    data class Add(override val habitUid: String, override val occurredOn: String, val amount: Int) : HabitRecordCommand
    data class Set(override val habitUid: String, override val occurredOn: String, val amount: Int) : HabitRecordCommand
    data class Clear(override val habitUid: String, override val occurredOn: String) : HabitRecordCommand
    data class ApplyPrimary(override val habitUid: String, override val occurredOn: String) : HabitRecordCommand
}

data class HabitRecordCommandDecision(
    val outcome: HabitActionOutcome,
    val targetCountForDay: Int? = null,
    val clearGoalWindow: Boolean = false,
)

object HabitRecordCommandPolicy {
    fun evaluate(
        habit: Habit,
        records: List<HabitRecord>,
        command: HabitRecordCommand,
        today: LocalDate? = null,
    ): HabitRecordCommandDecision {
        if (command.habitUid != habit.uid) return HabitRecordCommandDecision(HabitActionOutcome.NOT_FOUND)
        if (habit.isDeleted || habit.status != HabitStatus.ACTIVE) {
            return HabitRecordCommandDecision(HabitActionOutcome.INACTIVE)
        }
        val date = runCatching { LocalDate.parse(command.occurredOn) }.getOrNull()
            ?: return HabitRecordCommandDecision(HabitActionOutcome.NOT_DUE)
        if (!HabitScheduleEngine.state(habit.schedule, date).isDue) {
            return HabitRecordCommandDecision(HabitActionOutcome.NOT_DUE)
        }
        if (today != null && date > today) return HabitRecordCommandDecision(HabitActionOutcome.FUTURE_DATE)
        val current = records.asSequence()
            .filter { it.occurredOn == command.occurredOn && !it.isDeleted && it.status == HabitRecordStatus.COMPLETED }
            .sumOf(HabitRecord::count)
        val progress = HabitProgressEngine.progress(habit, records, date)
        val target = when (command) {
            is HabitRecordCommand.Clear -> 0
            is HabitRecordCommand.Set -> command.amount
            is HabitRecordCommand.Add -> current + command.amount
            is HabitRecordCommand.CheckOff -> when {
                current > 0 -> 0
                habit.goal.window == HabitGoalWindow.SCHEDULED_DAY -> habit.goal.targetCount
                else -> habit.tracking.defaultIncrement
            }
            is HabitRecordCommand.ApplyPrimary -> when {
                progress.isComplete -> 0
                habit.tracking.mode == HabitTrackingMode.QUANTITY -> {
                    current + minOf(habit.tracking.defaultIncrement, progress.targetCount - progress.completedCount)
                }
                current > 0 -> 0
                habit.goal.window == HabitGoalWindow.SCHEDULED_DAY -> habit.goal.targetCount
                else -> current + minOf(habit.tracking.defaultIncrement, progress.targetCount - progress.completedCount)
            }
        }
        if (target < 0 || command is HabitRecordCommand.Add && command.amount <= 0) {
            return HabitRecordCommandDecision(HabitActionOutcome.INVALID_AMOUNT)
        }
        val clearGoalWindow = command is HabitRecordCommand.ApplyPrimary && progress.isComplete
        return HabitRecordCommandDecision(
            outcome = if (target == current && !clearGoalWindow) HabitActionOutcome.NO_OP else HabitActionOutcome.APPLIED,
            targetCountForDay = target,
            clearGoalWindow = clearGoalWindow,
        )
    }
}
