package com.deadliner.sync.server.state

import com.deadliner.db.AppDatabase
import com.deadliner.sync.server.mapping.ServerSnapshotMapper
import com.deadliner.sync.server.mapping.ServerSyncEntityKey
import com.deadliner.sync.server.model.ServerSyncAction
import com.deadliner.sync.server.model.ServerSyncChange
import com.deadliner.sync.server.model.ServerSyncClientVersion
import com.deadliner.sync.server.model.ServerSyncMutation
import com.deadliner.sync.snapshot.SnapshotV3Root
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

internal data class ServerSyncStoredEntity(
    val key: ServerSyncEntityKey,
    val serverChangeId: Long,
    val clientVersion: ServerSyncClientVersion,
)

internal data class ServerSyncPendingMutation(
    val mutation: ServerSyncMutation,
    val createdAt: String,
    val attemptCount: Long,
    val lastError: String?,
)

/**
 * Durable, provider/account-scoped metadata. Business documents remain owned by Snapshot V5;
 * this store persists only replay state and never stores credentials.
 */
internal class ServerSyncStateStore(
    private val database: AppDatabase,
    private val json: Json,
) {
    fun ensureScope(scopeId: String) {
        database.serverSyncStateQueries.ensureScope(scopeId, Clock.System.now().toString())
    }

    fun cursor(scopeId: String): Long {
        ensureScope(scopeId)
        return database.serverSyncStateQueries.selectScope(scopeId) { _, cursor, _ -> cursor }
            .executeAsOne()
    }

    fun entityStates(scopeId: String): Map<ServerSyncEntityKey, ServerSyncStoredEntity> {
        ensureScope(scopeId)
        return database.serverSyncStateQueries.selectEntityStates(scopeId) {
                _, kind, uid, changeId, ts, ctr, dev ->
            val key = ServerSyncEntityKey(kind, uid)
            key to ServerSyncStoredEntity(key, changeId, ServerSyncClientVersion(ts, ctr.toInt(), dev))
        }.executeAsList().toMap()
    }

    /** Must be called in the same database transaction that applied the corresponding page. */
    fun recordPulledPage(scopeId: String, changes: List<ServerSyncChange>, nextCursor: Long) {
        changes.forEach { recordServerChange(scopeId, it) }
        database.serverSyncStateQueries.updateCursor(
            cursor = nextCursor,
            updated_at = Clock.System.now().toString(),
            scope_id = scopeId,
        )
    }

    fun recordAcceptedMutations(scopeId: String, accepted: List<Pair<String, ServerSyncChange>>) {
        database.transaction {
            accepted.forEach { (mutationId, change) ->
                recordServerChange(scopeId, change)
                database.serverSyncStateQueries.deleteOutboxMutation(scopeId, mutationId)
            }
        }
    }

    fun recordConflict(scopeId: String, mutationId: String, change: ServerSyncChange) {
        recordServerChange(scopeId, change)
        database.serverSyncStateQueries.deleteOutboxMutation(scopeId, mutationId)
    }

    fun reconcileOutbox(
        scopeId: String,
        snapshot: SnapshotV3Root,
        mapper: ServerSnapshotMapper,
        nextMutationId: () -> String,
    ): Long {
        ensureScope(scopeId)
        val states = entityStates(scopeId)
        val existing = allPending(scopeId).associateBy { it.mutation.key() }
        val mapped = mapper.mutations(
            snapshot = snapshot,
            baseChangeIds = states.mapValues { it.value.serverChangeId },
            mutationId = { _, _ -> PLACEHOLDER_MUTATION_ID },
        )
        val desired = mapped.filter { mutation ->
            val localVersion = requireNotNull(mutation.clientVersion)
            val serverVersion = states[mutation.key()]?.clientVersion
            serverVersion == null || localVersion.isNewerThan(serverVersion)
        }.associateBy { it.key() }

        database.transaction {
            existing.forEach { (key, pending) ->
                if (key !in desired) {
                    database.serverSyncStateQueries.deleteOutboxMutation(scopeId, pending.mutation.mutationId)
                }
            }
            desired.forEach { (key, mutation) ->
                val pending = existing[key]
                if (pending != null && pending.mutation.sameContentAs(mutation)) return@forEach
                val replacement = mutation.copy(mutationId = nextMutationId())
                database.serverSyncStateQueries.upsertOutbox(
                    scope_id = scopeId,
                    mutation_id = replacement.mutationId,
                    entity_kind = replacement.entityKind,
                    entity_uid = replacement.entityUid,
                    action = replacement.action.persisted(),
                    base_change_id = replacement.baseChangeId,
                    client_version_ts = requireNotNull(replacement.clientVersion).ts,
                    client_version_ctr = replacement.clientVersion.ctr.toLong(),
                    client_version_dev = replacement.clientVersion.dev,
                    payload_json = replacement.payload?.toString(),
                    created_at = Clock.System.now().toString(),
                )
            }
        }
        return pendingCount(scopeId)
    }

    fun pending(scopeId: String, limit: Int): List<ServerSyncPendingMutation> {
        require(limit > 0) { "Outbox limit must be positive." }
        ensureScope(scopeId)
        return database.serverSyncStateQueries.selectOutbox(scopeId, limit.toLong(), ::pending)
            .executeAsList()
    }

    fun pendingCount(scopeId: String): Long {
        ensureScope(scopeId)
        return database.serverSyncStateQueries.countOutbox(scopeId).executeAsOne()
    }

    fun markAttempt(scopeId: String, mutationId: String, error: String?) {
        database.serverSyncStateQueries.markOutboxAttempt(error, scopeId, mutationId)
    }

    private fun allPending(scopeId: String): List<ServerSyncPendingMutation> =
        database.serverSyncStateQueries.selectAllOutbox(scopeId, ::pending).executeAsList()

    private fun recordServerChange(scopeId: String, change: ServerSyncChange) {
        val changeId = change.serverVersion.changeId
        require(changeId > 0) { "Server change ID must be positive." }
        val version = requireNotNull(change.clientVersion) {
            "Server change ${change.entityKind}/${change.entityUid} is missing client_version."
        }
        val existing = database.serverSyncStateQueries.selectEntityState(
            scopeId,
            change.entityKind,
            change.entityUid,
        ) { _, _, _, storedChangeId, ts, ctr, dev ->
            storedChangeId to ServerSyncClientVersion(ts, ctr.toInt(), dev)
        }.executeAsOneOrNull()
        if (existing != null && existing.first > changeId) return
        require(existing == null || existing.first != changeId || existing.second == version) {
            "Server change ID $changeId changed logical version for ${change.entityKind}/${change.entityUid}."
        }
        database.serverSyncStateQueries.upsertEntityState(
            scope_id = scopeId,
            entity_kind = change.entityKind,
            entity_uid = change.entityUid,
            server_change_id = changeId,
            client_version_ts = version.ts,
            client_version_ctr = version.ctr.toLong(),
            client_version_dev = version.dev,
        )
    }

    private fun pending(
        @Suppress("UNUSED_PARAMETER") scopeId: String,
        mutationId: String,
        entityKind: String,
        entityUid: String,
        action: String,
        baseChangeId: Long,
        clientVersionTs: String,
        clientVersionCtr: Long,
        clientVersionDev: String,
        payloadJson: String?,
        createdAt: String,
        attemptCount: Long,
        lastError: String?,
    ) = ServerSyncPendingMutation(
        mutation = ServerSyncMutation(
            mutationId = mutationId,
            entityKind = entityKind,
            entityUid = entityUid,
            action = action.toAction(),
            baseChangeId = baseChangeId,
            clientVersion = ServerSyncClientVersion(
                clientVersionTs,
                clientVersionCtr.toInt(),
                clientVersionDev,
            ),
            payload = payloadJson?.let(json::parseToJsonElement),
        ),
        createdAt = createdAt,
        attemptCount = attemptCount,
        lastError = lastError,
    )

    private fun ServerSyncMutation.sameContentAs(other: ServerSyncMutation): Boolean =
        copy(mutationId = PLACEHOLDER_MUTATION_ID) == other.copy(mutationId = PLACEHOLDER_MUTATION_ID)

    private fun ServerSyncMutation.key() = ServerSyncEntityKey(entityKind, entityUid)

    private fun ServerSyncClientVersion.isNewerThan(other: ServerSyncClientVersion): Boolean =
        compareValuesBy(this, other, ServerSyncClientVersion::ts, ServerSyncClientVersion::ctr, ServerSyncClientVersion::dev) > 0

    private fun ServerSyncAction.persisted(): String = when (this) {
        ServerSyncAction.UPSERT -> "upsert"
        ServerSyncAction.DELETE -> "delete"
    }

    private fun String.toAction(): ServerSyncAction = when (this) {
        "upsert" -> ServerSyncAction.UPSERT
        "delete" -> ServerSyncAction.DELETE
        else -> error("Unknown server sync action '$this'.")
    }

    private companion object {
        const val PLACEHOLDER_MUTATION_ID = "pending"
    }
}
