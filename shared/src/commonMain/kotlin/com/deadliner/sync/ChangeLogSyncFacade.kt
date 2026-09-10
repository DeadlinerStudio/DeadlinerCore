package com.deadliner.sync

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.ChangeLogRepository
import com.deadliner.model.ChangeLogEntry
import com.deadliner.platform.PlatformLogger
import com.deadliner.sync.snapshot.SnapshotV3Root
import com.deadliner.sync.snapshot.SnapshotV3Store
import com.deadliner.sync.snapshot.SnapshotV3Validator
import com.deadliner.sync.snapshot.v3.LegacySnapshotV3Migrator
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Compatibility façade retained for the existing WebDAV and native bridges.
 *
 * Snapshot synchronization is snapshot-only. The local changelog remains available as an audit trail
 * for repository writes, but it never participates in network merge or acknowledgement.
 */
class ChangeLogSyncFacade(
    database: AppDatabase,
    @Suppress("UNUSED_PARAMETER") changeLog: ChangeLogRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val snapshotStore = SnapshotV3Store(database)
    private val logger = PlatformLogger()

    fun mergeRemotePayload(remotePayload: String?): ChangeLogSyncMergeResult {
        val remote = try {
            remotePayload?.takeIf(String::isNotBlank)?.let(::decodePayload)
                ?: ChangeLogSyncPayload()
        } catch (error: Exception) {
            logger.e("sync.webdav", "sync.payload.decode.failed diagnostic=${safePayloadDiagnostic(error)}")
            return failure("Invalid sync payload", error)
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
            logger.e("sync.webdav", "sync.payload.merge.failed diagnostic=${safePayloadDiagnostic(error)}")
            return failure("Failed to merge sync snapshot", error)
        }
    }

    private fun decodePayload(raw: String): ChangeLogSyncPayload {
        val currentFailure = runCatching {
            json.decodeFromString<ChangeLogSyncPayload>(raw).also(::requireCurrentFormat)
        }
        currentFailure.getOrNull()?.let { return it }

        return runCatching {
            val migrated = requireNotNull(LegacySnapshotV3Migrator.migrate(raw, json)) {
                "Payload is neither current V5 nor a supported legacy V3/V4 snapshot"
            }
            json.decodeFromString<ChangeLogSyncPayload>(migrated).also(::requireCurrentFormat)
        }.getOrElse { migrationFailure ->
            throw IllegalArgumentException(
                "${currentFailure.exceptionOrNull()?.message ?: "Current V5 decode failed"}; " +
                    "legacy migration failed: ${migrationFailure.message ?: migrationFailure::class.simpleName}",
                migrationFailure,
            )
        }
    }

    private fun requireCurrentFormat(payload: ChangeLogSyncPayload) {
        require(payload.formatVersion == ChangeLogSyncPayload.FORMAT_VERSION) {
            "Unsupported sync format ${payload.formatVersion}; expected ${ChangeLogSyncPayload.FORMAT_VERSION}"
        }
        payload.snapshot?.let { snapshot ->
            require(snapshot.formatVersion == SnapshotV3Root.FORMAT_VERSION) {
                "Unsupported snapshot format ${snapshot.formatVersion}; expected ${SnapshotV3Root.FORMAT_VERSION}"
            }
        }
    }

    private fun safePayloadDiagnostic(error: Throwable): String {
        val messages = generateSequence(error) { it.cause }
            .mapNotNull(Throwable::message)
            .joinToString(" ")
        val missingFields = REQUIRED_FIELD_PATTERN.findAll(messages)
            .map { it.groupValues[1] }
            .distinct()
            .take(4)
            .toList()
        return when {
            missingFields.isNotEmpty() -> "missingFields=${missingFields.joinToString(",")}"
            "Unsupported sync format" in messages -> "unsupportedSyncFormat"
            "Unsupported snapshot format" in messages -> "unsupportedSnapshotFormat"
            "Unsupported legacy habit period" in messages -> "unsupportedLegacyHabitPeriod"
            "Unsupported legacy habit goal type" in messages -> "unsupportedLegacyHabitGoalType"
            "Invalid v5 snapshot" in messages -> "snapshotValidation"
            "Unexpected JSON token" in messages -> "malformedJson"
            else -> "type=${error::class.simpleName ?: "UnknownPayloadFailure"}"
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
        const val FORMAT_VERSION = 5
    }
}

private val REQUIRED_FIELD_PATTERN = Regex("Field '([A-Za-z0-9_]+)' is required")

data class ChangeLogSyncMergeResult(
    val payload: String,
    val pendingIdsPayload: String,
    val remoteApplied: Int,
    val remoteIgnored: Int,
    val appliedResources: Set<SyncResourceKind> = emptySet(),
    val failureMessage: String? = null,
)
