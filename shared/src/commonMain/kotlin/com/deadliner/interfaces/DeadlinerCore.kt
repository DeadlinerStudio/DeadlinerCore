package com.deadliner.interfaces

import com.deadliner.domain.ai.agent.*
import com.deadliner.domain.ai.model.*
import com.deadliner.domain.ai.port.*
import kotlinx.coroutines.flow.SharedFlow

/** Public AI session contract; implementations are selected at build time. */
interface LifiRuntime : AutoCloseable {
    val events: SharedFlow<CoreEvent>
    suspend fun replayEvents(afterSeq: Long): AgentEventReplay
    suspend fun executeApprovedTool(actionId: String, confirmedArguments: String? = null): String
    suspend fun snapshotV2(): AgentV2Snapshot
    suspend fun readEventsV2(consumerId: String, afterSeq: Long): AgentV2Batch
    suspend fun ackEventsV2(consumerId: String, batchId: String, lastSeq: Long): Boolean
    suspend fun detachV2(consumerId: String)
    suspend fun startRunV2(text: String): String
    suspend fun processInput(text: String)
    suspend fun submitToolResultV2(toolCallId: String, result: ToolResult): Boolean
    suspend fun submitToolResult(toolCallId: String, result: ToolResult)
    suspend fun cancelRun()
}

/** Retains the host-facing constructor and API in public and private builds. */
class DeadlinerCore(
    config: AIConfig,
    orchestrator: AgentOrchestrator,
    toolExecutor: ToolExecutor,
    executePersistedTool: ((String, String, String) -> String)? = null,
    preparePersistedTool: ((String, ToolCall) -> Unit)? = null,
    readPersistedReceipt: ((String) -> AgentActionReceipt?)? = null,
) : LifiRuntime by createLifiRuntime(
    config, orchestrator, toolExecutor, executePersistedTool, preparePersistedTool, readPersistedReceipt,
)
