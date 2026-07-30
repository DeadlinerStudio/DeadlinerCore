package com.deadliner.domain.ai.port

import com.deadliner.domain.ai.model.MixedResult

fun interface ToolExecutor {
    suspend fun submitToolResult(toolCallId: String, result: ToolResult): MixedResult
}
