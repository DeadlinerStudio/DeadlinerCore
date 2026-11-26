package com.deadliner.domain.ai.model

import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitGoalWindow
import com.deadliner.domain.habit.HabitAlgorithms
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitTracking
import kotlinx.serialization.Serializable

@Serializable
sealed class ToolCall {
    abstract val agent: AgentRole?
    abstract val reason: String?
    abstract val executionMode: CapabilityExecutionMode?

    @Serializable
    data class ReadTasks(
        val timeRangeDays: Int? = null,
        val status: String? = null,
        val keywords: List<String> = emptyList(),
        val limit: Int = 20,
        val sort: String? = null,
        override val agent: AgentRole? = null,
        override val reason: String? = null,
        override val executionMode: CapabilityExecutionMode? = null,
    ) : ToolCall()

    @Serializable
    data class CreateTask(
        val name: String,
        val dueTime: String? = null,
        val note: String? = null,
        override val agent: AgentRole? = null,
        override val reason: String? = null,
        override val executionMode: CapabilityExecutionMode? = null,
    ) : ToolCall()

    @Serializable
    data class UpdateDeadline(
        val taskId: String,
        val newDueTime: String,
        override val agent: AgentRole? = null,
        override val reason: String? = null,
        override val executionMode: CapabilityExecutionMode? = null,
    ) : ToolCall()

    @Serializable
    data class ReadHabits(
        val keywords: List<String> = emptyList(),
        override val agent: AgentRole? = null,
        override val reason: String? = null,
        override val executionMode: CapabilityExecutionMode? = null,
    ) : ToolCall()

    @Serializable
    data class CreateHabit(
        val name: String,
        val schedule: HabitSchedule,
        val goal: HabitGoal = HabitGoal(),
        val tracking: HabitTracking = HabitTracking(),
        val reminder: HabitReminder? = null,
        val compatibilityWarning: String? = null,
        override val agent: AgentRole? = null,
        override val reason: String? = null,
        override val executionMode: CapabilityExecutionMode? = null,
    ) : ToolCall() {
        @Deprecated("Android compatibility only; serialize schedule/goal/tracking")
        val period: String get() = when {
            schedule.kind == HabitScheduleKind.ONCE -> "ONCE"
            schedule.algorithmKey == HabitAlgorithms.EBBINGHAUS_CLASSIC_V1 -> "EBBINGHAUS"
            goal.window == HabitGoalWindow.WEEK -> "WEEKLY"
            goal.window == HabitGoalWindow.MONTH -> "MONTHLY"
            else -> "DAILY"
        }
        @Deprecated("Android compatibility only; serialize goal.targetCount")
        val timesPerPeriod: Int get() = goal.targetCount
        @Deprecated("Android compatibility only; serialize goal.window")
        val goalType: String get() = if (goal.window == HabitGoalWindow.LIFETIME) "TOTAL" else "PER_PERIOD"
        @Deprecated("Android compatibility only; serialize goal.targetCount")
        val totalTarget: Int? get() = goal.targetCount.takeIf { goal.window == HabitGoalWindow.LIFETIME }
    }
}
