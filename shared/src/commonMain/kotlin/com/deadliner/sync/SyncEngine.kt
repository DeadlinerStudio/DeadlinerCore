package com.deadliner.sync

import com.deadliner.db.repository.ChangeLogRepository
import com.deadliner.model.ChangeLogEntry
import com.deadliner.sync.protocol.ChangeAcknowledgement
import com.deadliner.sync.protocol.RemoteChangeApplier
import com.deadliner.sync.protocol.SyncTransport

/** Coordinates changelog exchange without importing or querying a business table. */
class SyncEngine(
    private val changeLog: ChangeLogRepository,
    private val transport: SyncTransport,
    private val remoteChangeApplier: RemoteChangeApplier,
    private val conflictResolver: ConflictResolver = LastWriteWinsConflictResolver,
) {
    suspend fun synchronize(): SyncReport {
        val pending = changeLog.pending()
        val pushResult = transport.push(pending)
        pushResult.acknowledgements.groupBy(ChangeAcknowledgement::serverTimestamp).forEach { (timestamp, acks) ->
            changeLog.markSynced(acks.map(ChangeAcknowledgement::changeId), timestamp)
        }

        var applied = 0
        var ignored = 0
        val remoteChanges = transport.pull(changeLog.latestServerTimestamp())
        remoteChanges.forEach { incoming ->
            if (applyRemoteChange(incoming)) applied++ else ignored++
        }
        return SyncReport(pending.size, pushResult.acknowledgements.size, applied, ignored)
    }

    fun applyRemoteChange(incoming: ChangeLogEntry): Boolean {
        val existing = changeLog.latestFor(incoming.tableName, incoming.recordId)
        if (!conflictResolver.shouldApply(incoming, existing)) {
            changeLog.recordRemote(incoming)
            return false
        }

        remoteChangeApplier.apply(incoming) {
            changeLog.recordRemote(incoming)
        }
        return true
    }
}

data class SyncReport(
    val uploaded: Int,
    val acknowledged: Int,
    val remoteApplied: Int,
    val remoteIgnored: Int,
)
