package com.deadliner.domain.ai.model

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
        val period: String,
        val timesPerPeriod: Int = 1,
        val goalType: String = "per_period",
        val totalTarget: Int? = null,
        override val agent: AgentRole? = null,
        override val reason: String? = null,
        override val executionMode: CapabilityExecutionMode? = null,
    ) : ToolCall()
}
