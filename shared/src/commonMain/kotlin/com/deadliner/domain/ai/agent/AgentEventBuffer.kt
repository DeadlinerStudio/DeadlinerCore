package com.deadliner.domain.ai.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stable caller-owned batch: probes never consume; append cannot change a pending copy. */
class AgentEventBuffer {
    private val gate = Mutex()
    private val queue = ArrayDeque<String>()
    private var pending: List<String>? = null

    suspend fun append(eventJson: String) = gate.withLock { queue.addLast(eventJson) }
    suspend fun peek(): String = gate.withLock {
        val batch = pending ?: queue.take(64).also { pending = it }
        Json.encodeToString(batch)
    }
    suspend fun acknowledge(payload: String) = gate.withLock {
        val batch = pending ?: return@withLock
        check(Json.encodeToString(batch) == payload) { "EVENT_BATCH_MISMATCH" }
        repeat(batch.size) { queue.removeFirst() }
        pending = null
    }
    suspend fun count(): Int = gate.withLock { queue.size }
    suspend fun clear() = gate.withLock { queue.clear(); pending = null }
}
