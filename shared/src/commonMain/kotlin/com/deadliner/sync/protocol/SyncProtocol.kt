package com.deadliner.sync.protocol

import com.deadliner.model.ChangeLogEntry

/** Networking is deliberately outside this module; an adapter implements this port later. */
interface SyncTransport {
    suspend fun push(changes: List<ChangeLogEntry>): PushResult
    suspend fun pull(afterServerTimestamp: Long?): List<ChangeLogEntry>
}

data class PushResult(
    val acknowledgements: List<ChangeAcknowledgement>,
)

data class ChangeAcknowledgement(
    val changeId: String,
    val serverTimestamp: Long,
)

/**
 * Applies a winning remote change to a business repository. The implementation must execute
 * [recordApplied] in the same database transaction as its business-table write.
 */
fun interface RemoteChangeApplier {
    fun apply(change: ChangeLogEntry, recordApplied: () -> Unit)
}
