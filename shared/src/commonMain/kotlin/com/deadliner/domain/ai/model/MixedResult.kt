package com.deadliner.domain.ai.model

import com.deadliner.domain.ai.AiHabitProposal
import com.deadliner.domain.ai.AiTaskProposal
import kotlinx.serialization.Serializable

@Serializable
data class MixedResult(
    val primaryIntent: String? = null,
    val routedAgents: List<AgentRole> = emptyList(),
    val tasks: List<AiTaskProposal> = emptyList(),
    val habits: List<AiHabitProposal> = emptyList(),
    val retrievedTasks: List<AiTaskProposal> = emptyList(),
    val retrievedHabits: List<AiHabitProposal> = emptyList(),
    val newMemories: List<String> = emptyList(),
    val chatResponse: String? = null,
    val sessionSummary: String? = null,
    val userProfile: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
)
