package com.deadliner.domain.ai.port

import com.deadliner.domain.ai.model.ToolCall

/**
 * Optional capability implemented by an AI orchestrator that needs to retain
 * native-tool context until [ToolExecutor.submitToolResult] is called.
 *
 * The public core owns the ID sent to native clients, so the pending context
 * and the emitted [com.deadliner.domain.ai.model.CoreEvent.OnToolRequest]
 * always use exactly the same identifier.
 */
interface PendingToolCallRegistry {
    fun registerPendingToolCall(
        toolCallId: String,
        toolCall: ToolCall,
        originalText: String,
    )
}
