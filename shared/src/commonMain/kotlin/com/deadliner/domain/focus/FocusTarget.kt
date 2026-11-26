package com.deadliner.domain.focus

import com.deadliner.domain.habit.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
enum class FocusTargetKind { TASK, HABIT }

@Serializable
data class FocusTargetRef(val kind: FocusTargetKind, val uid: String) {
    init { require(uid.isNotBlank()) { "Focus target uid must not be blank" } }
}

/** Time can be invested off schedule; only the optional record action checks due dates. */
object HabitFocusPolicy {
    fun canFocus(habit: Habit?): Boolean = habit != null && !habit.isDeleted && habit.status == HabitStatus.ACTIVE

    fun recordDecision(
        habit: Habit,
        records: List<HabitRecord>,
        occurredOn: String,
        today: LocalDate,
        amount: Int? = null,
    ): HabitRecordCommandDecision {
        val date = runCatching { LocalDate.parse(occurredOn) }.getOrNull()
            ?: return HabitRecordCommandDecision(HabitActionOutcome.NOT_DUE)
        if (!canFocus(habit)) return HabitRecordCommandDecision(HabitActionOutcome.INACTIVE)
        if (date > today) return HabitRecordCommandDecision(HabitActionOutcome.FUTURE_DATE)
        if (!HabitScheduleEngine.state(habit.schedule, date).isDue) {
            return HabitRecordCommandDecision(HabitActionOutcome.NOT_DUE)
        }
        val relevant = records.filter { it.habitUid == habit.uid && !it.isDeleted && it.status == HabitRecordStatus.COMPLETED }
        val current = relevant.filter { it.occurredOn == occurredOn }.sumOf { it.count.toLong() }
        val range = HabitProgressEngine.goalRange(habit, date)
        val completed = relevant.filter { it.occurredOn in range.start.toString()..range.endInclusive.toString() }
            .sumOf { it.count.toLong() }
        val desired = if (habit.tracking.mode == HabitTrackingMode.QUANTITY) {
            if (amount == null || amount <= 0) return HabitRecordCommandDecision(HabitActionOutcome.INVALID_AMOUNT)
            current + amount
        } else {
            if (current > 0 || completed >= habit.goal.targetCount) {
                return HabitRecordCommandDecision(HabitActionOutcome.NO_OP, current.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            }
            if (habit.goal.window == HabitGoalWindow.SCHEDULED_DAY) habit.goal.targetCount.toLong()
            else minOf(habit.tracking.defaultIncrement.toLong(), habit.goal.targetCount - completed)
        }
        if (desired !in 1..Int.MAX_VALUE.toLong()) return HabitRecordCommandDecision(HabitActionOutcome.INVALID_AMOUNT)
        return HabitRecordCommandPolicy.evaluate(habit, relevant,
            HabitRecordCommand.Set(habit.uid, occurredOn, desired.toInt()), today)
    }
}

internal fun validateFocusTarget(taskUid: String, target: FocusTargetRef?) {
    when (target?.kind) {
        null, FocusTargetKind.TASK -> require(taskUid.isNotBlank() && (target == null || target.uid == taskUid))
        FocusTargetKind.HABIT -> require(taskUid.isEmpty()) { "A Habit focus cannot carry a Task uid" }
    }
}
