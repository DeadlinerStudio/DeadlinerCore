@file:Suppress("UNUSED_PARAMETER")
package com.deadliner.interfaces

import com.deadliner.domain.ai.agent.*
import com.deadliner.domain.ai.model.*
import com.deadliner.domain.ai.port.*
import com.deadliner.platform.PlatformIdentity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal fun createLifiRuntime(
    config: AIConfig,
    orchestrator: AgentOrchestrator,
    toolExecutor: ToolExecutor,
    executePersistedTool: ((String, String, String) -> String)?,
    preparePersistedTool: ((String, ToolCall) -> Unit)?,
    readPersistedReceipt: ((String) -> AgentActionReceipt?)?,
): LifiRuntime = UnavailableLifiRuntime()

private class UnavailableLifiRuntime : LifiRuntime {
    override val events = MutableSharedFlow<CoreEvent>().asSharedFlow()
    private val snapshot = AgentV2Snapshot(sessionId = PlatformIdentity.newId(), stage = "unavailable")
    override suspend fun snapshotV2(): AgentV2Snapshot = snapshot
    override suspend fun replayEvents(afterSeq: Long): AgentEventReplay = AgentEventReplay(
        false, AgentRuntimeSnapshot(0, null, "unavailable", null, null), emptyList(),
    )
    override suspend fun readEventsV2(consumerId: String, afterSeq: Long): AgentV2Batch =
        AgentV2Batch(batchId = "unavailable", afterSeq = 0, lastSeq = 0, resyncRequired = afterSeq != 0L,
            snapshot = snapshot, events = emptyList())
    override suspend fun ackEventsV2(consumerId: String, batchId: String, lastSeq: Long): Boolean = false
    override suspend fun detachV2(consumerId: String) {}
    override suspend fun startRunV2(text: String): String = throw LifiUnavailableException()
    override suspend fun processInput(text: String): Unit = throw LifiUnavailableException()
    override suspend fun executeApprovedTool(actionId: String, confirmedArguments: String?): String = throw LifiUnavailableException()
    override suspend fun submitToolResultV2(toolCallId: String, result: ToolResult): Boolean = throw LifiUnavailableException()
    override suspend fun submitToolResult(toolCallId: String, result: ToolResult): Unit = throw LifiUnavailableException()
    override suspend fun cancelRun() {}
    override fun close() {}
}
