package com.deadliner.interfaces

import com.deadliner.domain.ai.model.AIConfig
import com.deadliner.domain.ai.model.CoreEvent
import com.deadliner.domain.ai.model.MixedResult
import com.deadliner.domain.ai.port.AgentOrchestrator
import com.deadliner.domain.ai.port.PendingToolCallRegistry
import com.deadliner.domain.ai.port.ToolExecutor
import com.deadliner.domain.ai.port.ToolContinuationExecutor
import com.deadliner.domain.ai.port.ToolContinuationResult
import com.deadliner.domain.ai.port.ToolResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.datetime.Clock

class DeadlinerCore(
    private val config: AIConfig,
    private val orchestrator: AgentOrchestrator,
    private val toolExecutor: ToolExecutor,
) {
    private val _events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CoreEvent> = _events.asSharedFlow()

    suspend fun processInput(text: String) {
        val requestId = generateRequestId()
        _events.emit(CoreEvent.OnLifecycle(requestId, "started", "running"))

        try {
            _events.emit(CoreEvent.OnThinking("Supervisor", "routing"))
            val result = orchestrator.processMessage(text)
            handleMixedResult(requestId, result, originalText = text)
        } catch (e: Exception) {
            _events.emit(CoreEvent.OnError(e.message ?: "Unknown error"))
            _events.emit(CoreEvent.OnLifecycle(requestId, "failed", "error", e.message))
        }
    }

    suspend fun submitToolResult(toolCallId: String, result: ToolResult) {
        try {
            val continuation = if (toolExecutor is ToolContinuationExecutor) {
                toolExecutor.submitToolResultWithContext(toolCallId, result)
            } else {
                ToolContinuationResult(
                    originalText = "",
                    result = toolExecutor.submitToolResult(toolCallId, result),
                )
            }
            handleMixedResult(toolCallId, continuation.result, continuation.originalText)
        } catch (e: Exception) {
            _events.emit(CoreEvent.OnError(e.message ?: "Tool execution failed"))
        }
    }

    private suspend fun handleMixedResult(
        requestId: String,
        result: MixedResult,
        originalText: String,
    ) {
        if (result.toolCalls.isNotEmpty()) {
            result.toolCalls.forEachIndexed { index, call ->
                // The index keeps repeated identical tool calls from replacing
                // each other's pending context within one AI response.
                val callId = "${requestId}_${index}_${call.hashCode()}"
                (orchestrator as? PendingToolCallRegistry)?.registerPendingToolCall(
                    toolCallId = callId,
                    toolCall = call,
                    originalText = originalText,
                )
                _events.emit(CoreEvent.OnToolRequest(callId, call, call.executionMode))
            }
        } else {
            _events.emit(CoreEvent.OnFinish(result))
            _events.emit(CoreEvent.OnLifecycle(requestId, "completed", "success"))
        }
    }

    private fun generateRequestId(): String =
        "req_${Clock.System.now().toEpochMilliseconds()}"
}
