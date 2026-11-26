package com.deadliner.domain.ai.model

import kotlinx.serialization.Serializable

@Serializable
sealed class CoreEvent {
    @Serializable
    data class OnLifecycle(
        val requestId: String,
        val stage: String,
        val status: String,
        val message: String? = null,
    ) : CoreEvent()

    @Serializable
    data class OnThinking(
        val agentName: String,
        val phase: String,
        val message: String? = null,
    ) : CoreEvent()

    @Serializable
    data class OnTextStream(
        val chunk: String,
    ) : CoreEvent()

    @Serializable
    data class OnToolRequest(
        val id: String,
        val tool: ToolCall,
        val executionMode: CapabilityExecutionMode? = null,
    ) : CoreEvent()

    @Serializable
    data class OnFinish(
        val result: MixedResult,
    ) : CoreEvent()

    @Serializable
    data class OnMemoryCommitted(
        val addedMemories: List<String> = emptyList(),
        val profileUpdated: Boolean = false,
        val newRevision: Long = 0,
    ) : CoreEvent()

    @Serializable
    data class OnError(
        val message: String,
    ) : CoreEvent()
}
