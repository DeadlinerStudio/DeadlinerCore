package com.deadliner.domain.ai.port

import com.deadliner.domain.ai.model.MixedResult

/**
 * Optional richer continuation contract used by [com.deadliner.interfaces.DeadlinerCore].
 * It preserves the original user input when a tool continuation requests another tool.
 */
interface ToolContinuationExecutor : ToolExecutor {
    suspend fun submitToolResultWithContext(
        toolCallId: String,
        result: ToolResult,
    ): ToolContinuationResult
}

data class ToolContinuationResult(
    val originalText: String,
    val result: MixedResult,
)
