package com.deadliner.domain.ai

import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitGoalType
import com.deadliner.domain.habit.HabitGoalWindow
import com.deadliner.domain.habit.HabitAlgorithms
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitTracking
import kotlinx.serialization.Serializable

@Serializable
data class AiTaskProposal(
    val title: String,
    val dueAt: String? = null,
    val note: String? = null,
)

@Serializable
data class AiHabitProposal(
    val name: String,
    val schedule: HabitSchedule,
    val goal: HabitGoal = HabitGoal(),
    val tracking: HabitTracking = HabitTracking(),
    val reminder: HabitReminder? = null,
) {
    @Deprecated("Android compatibility only; use schedule")
    val period: HabitPeriod get() = legacyPeriod(schedule.kind, schedule.algorithmKey, goal.window)
    @Deprecated("Android compatibility only; use goal.targetCount")
    val timesPerPeriod: Int get() = goal.targetCount
    @Deprecated("Android compatibility only; use goal.window")
    val goalType: HabitGoalType get() = if (goal.window == HabitGoalWindow.LIFETIME) HabitGoalType.TOTAL else HabitGoalType.PER_PERIOD
    @Deprecated("Android compatibility only; use goal.targetCount")
    val totalTarget: Int? get() = goal.targetCount.takeIf { goal.window == HabitGoalWindow.LIFETIME }
}

private fun legacyPeriod(kind: HabitScheduleKind, algorithmKey: String?, window: HabitGoalWindow): HabitPeriod = when {
    kind == HabitScheduleKind.ONCE -> HabitPeriod.ONCE
    algorithmKey == HabitAlgorithms.EBBINGHAUS_CLASSIC_V1 -> HabitPeriod.EBBINGHAUS
    window == HabitGoalWindow.WEEK -> HabitPeriod.WEEKLY
    window == HabitGoalWindow.MONTH -> HabitPeriod.MONTHLY
    else -> HabitPeriod.DAILY
}

@Serializable
data class AiReadFilter(
    val timeRangeDays: Int? = null,
    val status: String? = null,
    val keywords: List<String> = emptyList(),
    val limit: Int = 20,
    val sort: String? = null,
)

@Serializable
data class AiExtractionResult(
    val primaryIntent: String? = null,
    val tasks: List<AiTaskProposal> = emptyList(),
    val habits: List<AiHabitProposal> = emptyList(),
    val newMemories: List<String> = emptyList(),
    val chatResponse: String? = null,
    val sessionSummary: String? = null,
    val userProfile: String? = null,
)
