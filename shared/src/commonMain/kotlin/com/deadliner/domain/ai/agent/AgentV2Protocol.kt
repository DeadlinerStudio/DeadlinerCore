package com.deadliner.domain.ai.agent

import com.deadliner.domain.ai.model.CoreEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class AgentV2Event(
    val protocolVersion: Int = 2,
    val sessionId: String,
    val runId: String?,
    val seq: Long,
    val eventId: String,
    val type: String,
    val timestamp: String,
    val payload: JsonObject,
    val compatibilityEvent: CoreEvent? = null,
)

@Serializable
data class AgentV2Snapshot(
    val protocolVersion: Int = 2,
    val sessionId: String,
    val runId: String? = null,
    val lastSeq: Long = 0,
    val stage: String = "idle",
    val body: String = "",
    val pending: CoreEvent.OnToolRequest? = null,
    val receipts: List<AgentActionReceipt> = emptyList(),
    val terminal: AgentV2Event? = null,
)

@Serializable
data class AgentV2Batch(
    val protocolVersion: Int = 2,
    val batchId: String,
    val afterSeq: Long,
    val lastSeq: Long,
    val resyncRequired: Boolean,
    val snapshot: AgentV2Snapshot,
    val events: List<AgentV2Event>,
)
