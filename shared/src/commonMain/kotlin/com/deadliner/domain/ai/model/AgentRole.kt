package com.deadliner.domain.ai.model

import kotlinx.serialization.Serializable

@Serializable
enum class AgentRole {
    Supervisor,
    TaskAgent,
    HabitAgent,
    ChatAgent;

    val label: String get() = name
}

@Serializable
enum class CapabilityExecutionMode {
    Auto,
    AskUser,
    Never,
}

@Serializable
enum class ToolCapability {
    READ_TASK_CONTEXT,
    READ_HABIT_CONTEXT,
    CREATE_TASK,
    CREATE_HABIT,
    UPDATE_TASK_DEADLINE,
}
