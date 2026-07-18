package com.deadliner.domain.ai

import com.deadliner.domain.habit.HabitGoalType
import com.deadliner.domain.habit.HabitPeriod
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
    val period: HabitPeriod,
    val timesPerPeriod: Int = 1,
    val goalType: HabitGoalType = HabitGoalType.PER_PERIOD,
    val totalTarget: Int? = null,
)

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
