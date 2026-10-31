package com.deadliner.domain.ai.agent

import com.deadliner.domain.ai.model.CoreEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AgentEventRecord(val seq: Long, val runId: String?, val event: CoreEvent)
@Serializable
data class AgentActionReceipt(val actionId: String, val tool: String, val resultJson: String)
@Serializable
data class AgentRuntimeSnapshot(
    val lastSeq: Long,
    val runId: String?,
    val stage: String,
    val pending: CoreEvent.OnToolRequest?,
    val terminal: CoreEvent?,
    val receipts: List<AgentActionReceipt> = emptyList(),
)
@Serializable
data class AgentEventReplay(val resyncRequired: Boolean, val snapshot: AgentRuntimeSnapshot, val events: List<AgentEventRecord>)
