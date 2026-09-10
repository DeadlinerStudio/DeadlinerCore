package com.deadliner.sync.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Extensible entity kind strings accepted by the generic DeadlinerServer V2 store. */
object ServerSyncEntityKind {
    const val CATEGORY = "category"
    const val TASK = "task"
    const val FOCUS_SESSION = "focus_session"
    const val HABIT = "habit"
    const val CAPTURE = "capture"
    const val MEMORY_FRAGMENT = "memory_fragment"
    const val MEMORY_USER_PROFILE = "memory_user_profile"
    const val USER_PROFILE = "user_profile"

    val snapshotV5Kinds: Set<String> = setOf(
        CATEGORY,
        TASK,
        FOCUS_SESSION,
        HABIT,
        CAPTURE,
        MEMORY_FRAGMENT,
        MEMORY_USER_PROFILE,
        USER_PROFILE,
    )

    @Deprecated("Use snapshotV5Kinds")
    val snapshotV4Kinds: Set<String> = snapshotV5Kinds
}

@Serializable
enum class ServerSyncAction {
    @SerialName("upsert")
    UPSERT,

    @SerialName("delete")
    DELETE,
}

@Serializable
data class ServerSyncClientVersion(
    val ts: String,
    val ctr: Int,
    val dev: String,
)

@Serializable
data class ServerSyncMutation(
    @SerialName("mutation_id")
    val mutationId: String,
    @SerialName("entity_kind")
    val entityKind: String,
    @SerialName("entity_uid")
    val entityUid: String,
    val action: ServerSyncAction,
    @SerialName("base_change_id")
    val baseChangeId: Long = 0,
    @SerialName("client_version")
    val clientVersion: ServerSyncClientVersion? = null,
    val payload: JsonElement? = null,
)

@Serializable
data class ServerSyncPushRequest(
    @SerialName("device_uid")
    val deviceUid: String,
    @SerialName("base_cursor")
    val baseCursor: Long = 0,
    val mutations: List<ServerSyncMutation>,
)

@Serializable
data class ServerSyncServerVersion(
    @SerialName("change_id")
    val changeId: Long,
    @SerialName("committed_at")
    val committedAt: String,
)

@Serializable
data class ServerSyncChange(
    @SerialName("entity_kind")
    val entityKind: String,
    @SerialName("entity_uid")
    val entityUid: String,
    val action: ServerSyncAction,
    @SerialName("server_version")
    val serverVersion: ServerSyncServerVersion,
    @SerialName("client_version")
    val clientVersion: ServerSyncClientVersion? = null,
    @SerialName("updated_by_device_uid")
    val updatedByDeviceUid: String? = null,
    val payload: JsonElement? = null,
)

@Serializable
enum class ServerSyncMutationStatus {
    @SerialName("applied")
    APPLIED,

    @SerialName("replayed")
    REPLAYED,

    @SerialName("conflict")
    CONFLICT,

    @SerialName("rejected")
    REJECTED,
}

@Serializable
data class ServerSyncMutationResult(
    @SerialName("mutation_id")
    val mutationId: String,
    val status: ServerSyncMutationStatus,
    val change: ServerSyncChange? = null,
    val latest: ServerSyncChange? = null,
    val reason: String? = null,
)

@Serializable
data class ServerSyncPushResponse(
    val results: List<ServerSyncMutationResult> = emptyList(),
    @SerialName("next_cursor")
    val nextCursor: Long = 0,
)

@Serializable
data class ServerSyncPullResponse(
    val changes: List<ServerSyncChange> = emptyList(),
    @SerialName("next_cursor")
    val nextCursor: Long = 0,
    @SerialName("has_more")
    val hasMore: Boolean = false,
)
