package com.deadliner.domain.ai.model

sealed class CoreEvent {
    data class OnLifecycle(
        val requestId: String,
        val stage: String,
        val status: String,
        val message: String? = null,
    ) : CoreEvent()

    data class OnThinking(
        val agentName: String,
        val phase: String,
        val message: String? = null,
    ) : CoreEvent()

    data class OnTextStream(
        val chunk: String,
    ) : CoreEvent()

    data class OnToolRequest(
        val id: String,
        val tool: ToolCall,
        val executionMode: CapabilityExecutionMode? = null,
    ) : CoreEvent()

    data class OnFinish(
        val result: MixedResult,
    ) : CoreEvent()

    data class OnMemoryCommitted(
        val addedMemories: List<String> = emptyList(),
        val profileUpdated: Boolean = false,
        val newRevision: Long = 0,
    ) : CoreEvent()

    data class OnError(
        val message: String,
    ) : CoreEvent()
}
