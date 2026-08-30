package com.deadliner.sync.server.mapping

import com.deadliner.domain.account.UserProfile
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.category.Category
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.task.Task
import com.deadliner.sync.server.model.ServerSyncAction
import com.deadliner.sync.server.model.ServerSyncChange
import com.deadliner.sync.server.model.ServerSyncClientVersion
import com.deadliner.sync.server.model.ServerSyncEntityKind
import com.deadliner.sync.server.model.ServerSyncMutation
import com.deadliner.sync.snapshot.SnapshotV3CaptureItem
import com.deadliner.sync.snapshot.SnapshotV3CategoryItem
import com.deadliner.sync.snapshot.SnapshotV3EntityVersion
import com.deadliner.sync.snapshot.SnapshotV3HabitDocument
import com.deadliner.sync.snapshot.SnapshotV3HabitItem
import com.deadliner.sync.snapshot.SnapshotV3MemoryFragmentItem
import com.deadliner.sync.snapshot.SnapshotV3MemoryUserProfileItem
import com.deadliner.sync.snapshot.SnapshotV3Root
import com.deadliner.sync.snapshot.SnapshotV3TaskItem
import com.deadliner.sync.snapshot.SnapshotV3UserProfileItem
import com.deadliner.sync.snapshot.SnapshotV3Version
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

data class ServerSyncEntityKey(
    val kind: String,
    val uid: String,
)

/**
 * The only mapping between Snapshot V4 aggregate documents and the generic Server V2 envelope.
 * Platform code must not duplicate these payload rules.
 */
class ServerSnapshotMapper(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
) {
    fun mutations(
        snapshot: SnapshotV3Root,
        baseChangeIds: Map<ServerSyncEntityKey, Long>,
        mutationId: (ServerSyncEntityKey, SnapshotV3EntityVersion) -> String,
    ): List<ServerSyncMutation> = buildList {
        snapshot.categories.forEach { item ->
            add(mutation(item.uid, ServerSyncEntityKind.CATEGORY, item.ver, item.deleted, item.doc?.let(json::encodeToJsonElement), baseChangeIds, mutationId))
        }
        snapshot.tasks.forEach { item ->
            add(mutation(item.uid, ServerSyncEntityKind.TASK, item.ver, item.deleted, item.doc?.let(json::encodeToJsonElement), baseChangeIds, mutationId))
        }
        snapshot.habits.forEach { item ->
            add(mutation(item.uid, ServerSyncEntityKind.HABIT, item.ver, item.deleted, item.doc?.let(json::encodeToJsonElement), baseChangeIds, mutationId))
        }
        snapshot.captures.forEach { item ->
            add(mutation(item.uid, ServerSyncEntityKind.CAPTURE, item.ver, item.deleted, item.doc?.let(json::encodeToJsonElement), baseChangeIds, mutationId))
        }
        snapshot.memoryFragments.forEach { item ->
            add(mutation(item.uid, ServerSyncEntityKind.MEMORY_FRAGMENT, item.ver, item.deleted, item.doc?.let(json::encodeToJsonElement), baseChangeIds, mutationId))
        }
        snapshot.memoryUserProfile?.let { item ->
            val key = ServerSyncEntityKey(ServerSyncEntityKind.MEMORY_USER_PROFILE, MEMORY_USER_PROFILE_UID)
            add(
                ServerSyncMutation(
                    mutationId = mutationId(key, item.ver),
                    entityKind = key.kind,
                    entityUid = key.uid,
                    action = ServerSyncAction.UPSERT,
                    baseChangeId = baseChangeIds[key] ?: 0,
                    clientVersion = item.ver.toServer(),
                    payload = json.encodeToJsonElement(MemoryUserProfilePayload(item.content)),
                ),
            )
        }
        snapshot.userProfiles.forEach { item ->
            add(mutation(item.uid, ServerSyncEntityKind.USER_PROFILE, item.ver, item.deleted, item.doc?.let(json::encodeToJsonElement), baseChangeIds, mutationId))
        }
    }

    /** Converts one fully downloaded pull page into a dependency-ordered partial snapshot. */
    fun snapshot(changes: List<ServerSyncChange>): SnapshotV3Root {
        require(changes.isNotEmpty()) { "Cannot create a server snapshot from an empty change page." }
        val categories = mutableListOf<SnapshotV3CategoryItem>()
        val tasks = mutableListOf<SnapshotV3TaskItem>()
        val habits = mutableListOf<SnapshotV3HabitItem>()
        val captures = mutableListOf<SnapshotV3CaptureItem>()
        val memories = mutableListOf<SnapshotV3MemoryFragmentItem>()
        var memoryUserProfile: SnapshotV3MemoryUserProfileItem? = null
        val userProfiles = mutableListOf<SnapshotV3UserProfileItem>()

        changes.forEach { change ->
            val version = requireNotNull(change.clientVersion) {
                "Server change ${change.entityKind}/${change.entityUid} is missing client_version."
            }.toSnapshot()
            val deleted = change.action == ServerSyncAction.DELETE
            if (!deleted) requireNotNull(change.payload) {
                "Server upsert ${change.entityKind}/${change.entityUid} is missing payload."
            }
            when (change.entityKind) {
                ServerSyncEntityKind.CATEGORY -> categories += SnapshotV3CategoryItem(
                    change.entityUid,
                    version,
                    deleted,
                    change.decodeOrNull<Category>(deleted).also { it?.requireUid(change) },
                )
                ServerSyncEntityKind.TASK -> tasks += SnapshotV3TaskItem(
                    change.entityUid,
                    version,
                    deleted,
                    change.decodeOrNull<Task>(deleted).also { it?.requireUid(change) },
                )
                ServerSyncEntityKind.HABIT -> habits += SnapshotV3HabitItem(
                    change.entityUid,
                    version,
                    deleted,
                    change.decodeOrNull<SnapshotV3HabitDocument>(deleted).also { document ->
                        document?.habit?.requireUid(change)
                    },
                )
                ServerSyncEntityKind.CAPTURE -> captures += SnapshotV3CaptureItem(
                    change.entityUid,
                    version,
                    deleted,
                    change.decodeOrNull<CaptureItem>(deleted).also { it?.requireUid(change) },
                )
                ServerSyncEntityKind.MEMORY_FRAGMENT -> memories += SnapshotV3MemoryFragmentItem(
                    change.entityUid,
                    version,
                    deleted,
                    change.decodeOrNull<MemoryFragment>(deleted).also { it?.requireUid(change) },
                )
                ServerSyncEntityKind.MEMORY_USER_PROFILE -> {
                    require(change.entityUid == MEMORY_USER_PROFILE_UID) {
                        "Memory profile must use entity UID '$MEMORY_USER_PROFILE_UID'."
                    }
                    require(!deleted) { "Snapshot V4 cannot represent a deleted memory user profile." }
                    val payload = change.decode<MemoryUserProfilePayload>()
                    memoryUserProfile = SnapshotV3MemoryUserProfileItem(version, payload.content)
                }
                ServerSyncEntityKind.USER_PROFILE -> userProfiles += SnapshotV3UserProfileItem(
                    change.entityUid,
                    version,
                    deleted,
                    change.decodeOrNull<UserProfile>(deleted).also { it?.requireUid(change) },
                )
                else -> error("Unsupported server entity kind '${change.entityKind}'.")
            }
        }

        val latest = changes.maxBy { it.serverVersion.changeId }.serverVersion
        return SnapshotV3Root(
            version = SnapshotV3Version(latest.committedAt, SERVER_DEVICE_ID),
            categories = categories,
            tasks = tasks,
            habits = habits,
            captures = captures,
            memoryFragments = memories,
            memoryUserProfile = memoryUserProfile,
            userProfiles = userProfiles,
        )
    }

    private fun mutation(
        uid: String,
        kind: String,
        version: SnapshotV3EntityVersion,
        deleted: Boolean,
        payload: JsonElement?,
        baseChangeIds: Map<ServerSyncEntityKey, Long>,
        mutationId: (ServerSyncEntityKey, SnapshotV3EntityVersion) -> String,
    ): ServerSyncMutation {
        val key = ServerSyncEntityKey(kind, uid)
        require(deleted || payload != null) { "Snapshot upsert $kind/$uid is missing its aggregate document." }
        return ServerSyncMutation(
            mutationId = mutationId(key, version),
            entityKind = kind,
            entityUid = uid,
            action = if (deleted) ServerSyncAction.DELETE else ServerSyncAction.UPSERT,
            baseChangeId = baseChangeIds[key] ?: 0,
            clientVersion = version.toServer(),
            payload = if (deleted) null else payload,
        )
    }

    private inline fun <reified T> ServerSyncChange.decode(): T =
        json.decodeFromJsonElement(requireNotNull(payload))

    private inline fun <reified T> ServerSyncChange.decodeOrNull(deleted: Boolean): T? =
        if (deleted) null else decode()

    private fun SnapshotV3EntityVersion.toServer() = ServerSyncClientVersion(ts, ctr, dev)
    private fun ServerSyncClientVersion.toSnapshot() = SnapshotV3EntityVersion(ts, ctr, dev)

    private fun Category.requireUid(change: ServerSyncChange) = require(uid == change.entityUid) { uidMismatch(change, uid) }
    private fun Task.requireUid(change: ServerSyncChange) = require(uid == change.entityUid) { uidMismatch(change, uid) }
    private fun Habit.requireUid(change: ServerSyncChange) = require(uid == change.entityUid) { uidMismatch(change, uid) }
    private fun CaptureItem.requireUid(change: ServerSyncChange) = require(uid == change.entityUid) { uidMismatch(change, uid) }
    private fun MemoryFragment.requireUid(change: ServerSyncChange) = require(uid == change.entityUid) { uidMismatch(change, uid) }
    private fun UserProfile.requireUid(change: ServerSyncChange) = require(uid == change.entityUid) { uidMismatch(change, uid) }
    private fun uidMismatch(change: ServerSyncChange, payloadUid: String) =
        "Server ${change.entityKind} envelope UID '${change.entityUid}' does not match payload UID '$payloadUid'."

    private companion object {
        const val MEMORY_USER_PROFILE_UID = "singleton"
        const val SERVER_DEVICE_ID = "deadliner-server"
    }
}

@Serializable
private data class MemoryUserProfilePayload(
    val content: String,
)
