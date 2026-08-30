package com.deadliner.sync

import com.deadliner.platform.PlatformLogger
import com.deadliner.sync.transport.SyncBlob
import com.deadliner.sync.transport.SyncBlobTransport
import com.deadliner.sync.transport.SyncTransportPreconditionFailedException
import com.deadliner.sync.transport.SyncWriteCondition
import com.deadliner.sync.webdav.WebDavSyncTransport
import com.deadliner.sync.webdav.WebDavTransportConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
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
    private val logger = PlatformLogger()

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
    ): WebDavChangeLogSyncOutcome = singleFlight {
        synchronizeSafelyInternal {
            synchronizeInternal(configuration)
        }
    }

    /** Same protected boundary for native transports such as iCloud. */
    suspend fun synchronizeSafely(
        transport: SyncBlobTransport,
    ): WebDavChangeLogSyncOutcome = singleFlight {
        synchronizeSafelyInternal {
            synchronizeInternal(transport)
        }
    }

    @Throws(Exception::class)
    suspend fun synchronize(configuration: WebDavTransportConfiguration): WebDavChangeLogSyncResult = singleFlight {
        synchronizeInternal(configuration)
    }

    private suspend fun synchronizeInternal(
        configuration: WebDavTransportConfiguration,
    ): WebDavChangeLogSyncResult {
        val transport = WebDavSyncTransport(configuration)
        return try {
            synchronizeInternal(transport)
        } finally {
            transport.close()
        }
    }

    @Throws(Exception::class)
    suspend fun synchronize(transport: SyncBlobTransport): WebDavChangeLogSyncResult = singleFlight {
        synchronizeInternal(transport)
    }

    private suspend fun synchronizeInternal(transport: SyncBlobTransport): WebDavChangeLogSyncResult {
        logger.i("sync.webdav", "sync.started")
        var remote = transport.read(PAYLOAD_PATH)
        logger.d("sync.webdav", "sync.remote.read present=" + (remote != null))
        var merge = merge(remote)

        try {
            write(transport, merge, remote)
        } catch (_: SyncTransportPreconditionFailedException) {
            logger.w("sync.webdav", "sync.write.preconditionFailed retry=1")
            remote = transport.read(PAYLOAD_PATH)
            logger.d("sync.webdav", "sync.remote.reread present=" + (remote != null))
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
        result.failureMessage?.let { failureMessage ->
            // The remote payload can contain user data. Keep the detailed
            // reason in the outcome, but make the durable diagnostic event
            // safe to export.
            logger.e("sync.webdav", "sync.remote.merge.failed reason=invalidPayload")
            throw IllegalArgumentException(failureMessage)
        }
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
        val conditionName = if (remote?.version != null) "matchVersion" else if (remote == null) "createOnly" else "unconditional"
        logger.d("sync.webdav", "sync.write condition=" + conditionName)
        transport.write(PAYLOAD_PATH, merge.payload.encodeToByteArray(), condition)
    }

    private suspend fun synchronizeSafelyInternal(
        operation: suspend () -> WebDavChangeLogSyncResult,
    ): WebDavChangeLogSyncOutcome = try {
        operation().let {
            logger.i(
                "sync.webdav",
                "sync.succeeded remoteApplied=" + it.remoteApplied + " remoteIgnored=" + it.remoteIgnored,
            )
            WebDavChangeLogSyncOutcome(
                succeeded = true,
                remoteApplied = it.remoteApplied,
                remoteIgnored = it.remoteIgnored,
                appliedResources = it.appliedResources,
            )
        }
    } catch (error: CancellationException) {
        // Cancellation is control flow (for example, app/database shutdown), not a sync failure.
        // Preserve structured concurrency and avoid reporting a false failed outcome.
        throw error
    } catch (error: Throwable) {
        val failureType = error::class.simpleName ?: "UnknownSyncFailure"
        logger.e("sync.webdav", "sync.failed type=" + failureType)
        WebDavChangeLogSyncOutcome(
            succeeded = false,
            failureMessage = error.message ?: error::class.simpleName ?: "Unknown sync transport failure",
        )
    }

    private suspend fun <T> singleFlight(operation: suspend () -> T): T {
        if (!syncMutex.tryLock()) {
            logger.i("sync.webdav", "sync.serialized waitingForExistingRun=true")
            syncMutex.lock()
        }
        return try {
            operation()
        } finally {
            syncMutex.unlock()
        }
    }

    private companion object {
        val syncMutex = Mutex()
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
