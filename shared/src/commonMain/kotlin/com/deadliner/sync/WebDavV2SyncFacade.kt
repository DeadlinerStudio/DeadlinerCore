package com.deadliner.sync

import com.deadliner.sync.transport.SyncBlob
import com.deadliner.sync.transport.SyncBlobTransport
import com.deadliner.sync.transport.SyncTransportPreconditionFailedException
import com.deadliner.sync.transport.SyncWriteCondition
import com.deadliner.sync.webdav.WebDavSyncTransport
import com.deadliner.sync.webdav.WebDavTransportConfiguration

/**
 * Transport-neutral V2 orchestration. The three existing remote paths and
 * `LegacyV2SyncFacade` payloads are deliberately retained unchanged.
 */
class WebDavV2SyncFacade(
    private val legacyV2Sync: LegacyV2SyncFacade,
) {
    /** Swift and other platform callers receive transport failures as errors, never fatal exceptions. */
    @Throws(Exception::class)
    suspend fun synchronize(configuration: WebDavTransportConfiguration): WebDavV2SyncResult {
        val transport = WebDavSyncTransport(configuration)
        return try {
            synchronize(transport)
        } finally {
            transport.close()
        }
    }

    @Throws(Exception::class)
    suspend fun synchronize(transport: SyncBlobTransport): WebDavV2SyncResult {
        return synchronize(transport, loadRemotePayloads(transport))
    }

    /**
     * Imports V2 only when this provider already owns at least one legacy
     * payload. This intentionally performs no collection creation or writes
     * for new providers, so V2 remains an upgrade path rather than a runtime
     * protocol choice.
     */
    @Throws(Exception::class)
    suspend fun migrateExistingLegacyPayloads(
        configuration: WebDavTransportConfiguration,
    ): WebDavLegacyV2MigrationResult {
        val transport = WebDavSyncTransport(configuration)
        return try {
            migrateExistingLegacyPayloads(transport)
        } finally {
            transport.close()
        }
    }

    @Throws(Exception::class)
    suspend fun migrateExistingLegacyPayloads(
        transport: SyncBlobTransport,
    ): WebDavLegacyV2MigrationResult {
        val remote = loadRemotePayloads(transport)
        if (!remote.hasLegacyPayload) {
            return WebDavLegacyV2MigrationResult(legacyPayloadFound = false)
        }
        val result = synchronize(transport, remote)
        return WebDavLegacyV2MigrationResult(
            legacyPayloadFound = true,
            remoteApplied = result.remoteApplied,
            remoteIgnored = result.remoteIgnored,
        )
    }

    private suspend fun synchronize(
        transport: SyncBlobTransport,
        initialRemote: V2RemotePayloads,
    ): WebDavV2SyncResult {
        var remote = initialRemote
        var merge = merge(remote)

        try {
            upload(transport, merge, remote)
        } catch (_: SyncTransportPreconditionFailedException) {
            remote = loadRemotePayloads(transport)
            merge = merge(remote)
            upload(transport, merge, remote)
        }

        return WebDavV2SyncResult(merge.remoteApplied, merge.remoteIgnored)
    }

    private fun merge(remote: V2RemotePayloads): LegacyV2SyncMergeResult {
        val result = legacyV2Sync.mergeRemotePayloads(
            taskPayload = remote.task.payload,
            habitPayload = remote.habit.payload,
            categoryPayload = remote.category.payload,
        )
        result.failureMessage?.let(::throwInvalidPayload)
        return result
    }

    private suspend fun upload(
        transport: SyncBlobTransport,
        merge: LegacyV2SyncMergeResult,
        remote: V2RemotePayloads,
    ) {
        if (!transport.ensureCollection(ROOT_COLLECTION)) {
            throw IllegalStateException("Could not create $ROOT_COLLECTION collection.")
        }
        transport.write(CATEGORY_PATH, merge.categoryPayload.encodeToByteArray(), remote.category.writeCondition)
        transport.write(TASK_PATH, merge.taskPayload.encodeToByteArray(), remote.task.writeCondition)
        transport.write(HABIT_PATH, merge.habitPayload.encodeToByteArray(), remote.habit.writeCondition)
    }

    private suspend fun loadRemotePayloads(transport: SyncBlobTransport): V2RemotePayloads = V2RemotePayloads(
        task = transport.read(TASK_PATH).toRemotePayload(),
        habit = transport.read(HABIT_PATH).toRemotePayload(),
        category = transport.read(CATEGORY_PATH).toRemotePayload(),
    )

    private fun SyncBlob?.toRemotePayload(): V2RemotePayload = when (this) {
        null -> V2RemotePayload(payload = null, writeCondition = SyncWriteCondition.CreateOnly)
        else -> V2RemotePayload(
            payload = bytes.decodeToString(),
            writeCondition = version?.let(SyncWriteCondition::MatchVersion) ?: SyncWriteCondition.Unconditional,
        )
    }

    private fun throwInvalidPayload(message: String): Nothing =
        throw IllegalArgumentException("Invalid V2 sync payload: $message")

    private companion object {
        const val ROOT_COLLECTION = "Deadliner"
        const val TASK_PATH = "$ROOT_COLLECTION/snapshot-v2.json"
        const val HABIT_PATH = "$ROOT_COLLECTION/habit-snapshot-v2.json"
        const val CATEGORY_PATH = "$ROOT_COLLECTION/category-snapshot-v2.json"
    }
}

data class WebDavV2SyncResult(
    val remoteApplied: Int,
    val remoteIgnored: Int,
)

private data class V2RemotePayloads(
    val task: V2RemotePayload,
    val habit: V2RemotePayload,
    val category: V2RemotePayload,
)

private val V2RemotePayloads.hasLegacyPayload: Boolean
    get() = hasLegacyV2Payload(task.payload, habit.payload, category.payload)

/** Kept separate so all platforms use the same definition of a legacy provider. */
internal fun hasLegacyV2Payload(
    taskPayload: String?,
    habitPayload: String?,
    categoryPayload: String?,
): Boolean = taskPayload != null || habitPayload != null || categoryPayload != null

private data class V2RemotePayload(
    val payload: String?,
    val writeCondition: SyncWriteCondition,
)

data class WebDavLegacyV2MigrationResult(
    val legacyPayloadFound: Boolean,
    val remoteApplied: Int = 0,
    val remoteIgnored: Int = 0,
)
