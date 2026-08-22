package com.deadliner.sync

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.ChangeLogRepository
import com.deadliner.model.ChangeLogEntry
import com.deadliner.sync.snapshot.SnapshotV3Root
import com.deadliner.sync.snapshot.SnapshotV3Store
import com.deadliner.sync.snapshot.SnapshotV3Validator
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Compatibility façade retained for the existing WebDAV and native bridges.
 *
 * V3 synchronization is snapshot-only. The local changelog remains available as an audit trail
 * for repository writes, but it never participates in network merge or acknowledgement.
 */
class ChangeLogSyncFacade(
    database: AppDatabase,
    @Suppress("UNUSED_PARAMETER") changeLog: ChangeLogRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val snapshotStore = SnapshotV3Store(database)

    fun mergeRemotePayload(remotePayload: String?): ChangeLogSyncMergeResult {
        val remote = try {
            remotePayload?.takeIf(String::isNotBlank)?.let { json.decodeFromString<ChangeLogSyncPayload>(it) }
                ?: ChangeLogSyncPayload()
        } catch (error: Exception) {
            return failure("Invalid V3 sync payload", error)
        }
        try {
            require(remote.formatVersion == ChangeLogSyncPayload.FORMAT_VERSION) {
                "Unsupported sync format ${remote.formatVersion}; expected ${ChangeLogSyncPayload.FORMAT_VERSION}"
            }
            remote.snapshot?.let(SnapshotV3Validator::requireValid)
            val merged = snapshotStore.merge(remote.snapshot)
            return ChangeLogSyncMergeResult(
                payload = json.encodeToString(ChangeLogSyncPayload(snapshot = merged.snapshot)),
                pendingIdsPayload = "[]",
                remoteApplied = merged.remoteApplied,
                remoteIgnored = merged.remoteIgnored,
                appliedResources = merged.appliedResources,
            )
        } catch (error: Throwable) {
            return failure("Failed to merge V3 snapshot", error)
        }
    }

    /** Kept for binary/source compatibility; snapshot synchronization has nothing to acknowledge. */
    fun acknowledgePending(pendingIdsPayload: String, serverTimestamp: Long) = Unit

    private fun failure(prefix: String, error: Throwable) = ChangeLogSyncMergeResult(
        payload = "",
        pendingIdsPayload = "[]",
        remoteApplied = 0,
        remoteIgnored = 0,
        failureMessage = "$prefix: ${error.message ?: error::class.simpleName}",
    )
}

/**
 * `changes` is retained only to decode the unsafe hybrid payload released before snapshot-only
 * V3. It is deliberately ignored and is never emitted by V3.
 */
@Serializable
data class ChangeLogSyncPayload(
    val formatVersion: Int = FORMAT_VERSION,
    val snapshot: SnapshotV3Root? = null,
    val changes: List<ChangeLogEntry> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION = 4
    }
}

data class ChangeLogSyncMergeResult(
    val payload: String,
    val pendingIdsPayload: String,
    val remoteApplied: Int,
    val remoteIgnored: Int,
    val appliedResources: Set<SyncResourceKind> = emptySet(),
    val failureMessage: String? = null,
)
