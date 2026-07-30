package com.deadliner.sync

import com.deadliner.sync.transport.SyncBlob
import com.deadliner.sync.transport.SyncBlobTransport
import com.deadliner.sync.transport.SyncTransportPreconditionFailedException
import com.deadliner.sync.transport.SyncWriteCondition
import com.deadliner.sync.webdav.WebDavSyncTransport
import com.deadliner.sync.webdav.WebDavTransportConfiguration
import kotlinx.datetime.Clock

/**
 * WebDAV binding for the experimental change-log protocol.
 *
 * It deliberately shares the same conditional blob transport as V2. Changes
 * to changelog entity coverage remain independent from HTTP behavior.
 */
class WebDavChangeLogSyncFacade(
    private val changeLogSync: ChangeLogSyncFacade,
) {
    @Throws(Exception::class)
    suspend fun synchronize(configuration: WebDavTransportConfiguration): WebDavChangeLogSyncResult {
        val transport = WebDavSyncTransport(configuration)
        return try {
            synchronize(transport)
        } finally {
            transport.close()
        }
    }

    @Throws(Exception::class)
    suspend fun synchronize(transport: SyncBlobTransport): WebDavChangeLogSyncResult {
        var remote = transport.read(PAYLOAD_PATH)
        var merge = merge(remote)

        try {
            write(transport, merge, remote)
        } catch (_: SyncTransportPreconditionFailedException) {
            remote = transport.read(PAYLOAD_PATH)
            merge = merge(remote)
            write(transport, merge, remote)
        }

        changeLogSync.acknowledgePending(
            pendingIdsPayload = merge.pendingIdsPayload,
            serverTimestamp = Clock.System.now().toEpochMilliseconds(),
        )
        return WebDavChangeLogSyncResult(
            remoteApplied = merge.remoteApplied,
            remoteIgnored = merge.remoteIgnored,
        )
    }

    private fun merge(remote: SyncBlob?): ChangeLogSyncMergeResult {
        val result = changeLogSync.mergeRemotePayload(remote?.bytes?.decodeToString())
        result.failureMessage?.let { throw IllegalArgumentException(it) }
        return result
    }

    private suspend fun write(
        transport: SyncBlobTransport,
        merge: ChangeLogSyncMergeResult,
        remote: SyncBlob?,
    ) {
        if (!transport.ensureCollection(ROOT_COLLECTION)) {
            throw IllegalStateException("Could not create $ROOT_COLLECTION collection.")
        }
        val condition = remote?.version?.let(SyncWriteCondition::MatchVersion)
            ?: if (remote == null) SyncWriteCondition.CreateOnly else SyncWriteCondition.Unconditional
        transport.write(PAYLOAD_PATH, merge.payload.encodeToByteArray(), condition)
    }

    private companion object {
        const val ROOT_COLLECTION = "Deadliner"
        const val PAYLOAD_PATH = "$ROOT_COLLECTION/kmp-changelog-v1.json"
    }
}

data class WebDavChangeLogSyncResult(
    val remoteApplied: Int,
    val remoteIgnored: Int,
)
