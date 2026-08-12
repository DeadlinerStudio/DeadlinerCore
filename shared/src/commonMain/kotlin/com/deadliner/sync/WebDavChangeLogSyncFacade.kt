package com.deadliner.sync

import com.deadliner.sync.transport.SyncBlob
import com.deadliner.sync.transport.SyncBlobTransport
import com.deadliner.sync.transport.SyncTransportPreconditionFailedException
import com.deadliner.sync.transport.SyncWriteCondition
import com.deadliner.sync.webdav.WebDavSyncTransport
import com.deadliner.sync.webdav.WebDavTransportConfiguration
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

/**
 * WebDAV binding for the experimental change-log protocol.
 *
 * It deliberately shares the same conditional blob transport as V2. Changes
 * to changelog entity coverage remain independent from HTTP behavior.
 */
class WebDavChangeLogSyncFacade(
    private val changeLogSync: ChangeLogSyncFacade,
) {
    /**
     * iOS-facing boundary for a network operation.
     *
     * Kotlin/Native aborts when an exception from a Darwin URLSession callback
     * crosses an Objective-C suspend export without being handled on the Kotlin
     * side. In particular, Ktor can surface a `ClosedByteChannelException` when
     * the system closes a request while it is being read or written. Keep that
     * transport failure in KMP and return a regular failed sync outcome instead.
     */
    suspend fun synchronizeSafely(
        configuration: WebDavTransportConfiguration,
    ): WebDavChangeLogSyncOutcome = synchronizeSafely {
        synchronize(configuration)
    }

    /** Same protected boundary for native transports such as iCloud. */
    suspend fun synchronizeSafely(
        transport: SyncBlobTransport,
    ): WebDavChangeLogSyncOutcome = synchronizeSafely {
        synchronize(transport)
    }

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
            appliedResources = merge.appliedResources,
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

    private suspend fun synchronizeSafely(
        operation: suspend () -> WebDavChangeLogSyncResult,
    ): WebDavChangeLogSyncOutcome = try {
        operation().let {
            WebDavChangeLogSyncOutcome(
                succeeded = true,
                remoteApplied = it.remoteApplied,
                remoteIgnored = it.remoteIgnored,
                appliedResources = it.appliedResources,
            )
        }
    } catch (error: Throwable) {
        WebDavChangeLogSyncOutcome(
            succeeded = false,
            failureMessage = error.message ?: error::class.simpleName ?: "Unknown sync transport failure",
        )
    }

    private companion object {
        const val ROOT_COLLECTION = "Deadliner"
        const val PAYLOAD_PATH = "$ROOT_COLLECTION/kmp-changelog-v1.json"
    }
}

@Serializable
data class WebDavChangeLogSyncResult(
    val remoteApplied: Int,
    val remoteIgnored: Int,
    val appliedResources: Set<SyncResourceKind> = emptySet(),
)

/** A non-throwing result suitable for calls across the Swift/Kotlin boundary. */
@Serializable
data class WebDavChangeLogSyncOutcome(
    val succeeded: Boolean,
    val remoteApplied: Int = 0,
    val remoteIgnored: Int = 0,
    val appliedResources: Set<SyncResourceKind> = emptySet(),
    val failureMessage: String? = null,
)

@Serializable
enum class SyncResourceKind {
    CATEGORY,
    TASK,
    HABIT,
    CAPTURE,
    MEMORY,
    PROFILE,
}
