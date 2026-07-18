package com.deadliner.domain.sync

import kotlinx.serialization.Serializable

@Serializable
data class EntityVersion(
    val timestamp: String,
    val counter: Int,
    val deviceId: String,
) : Comparable<EntityVersion> {
    override fun compareTo(other: EntityVersion): Int =
        compareValuesBy(this, other, EntityVersion::timestamp, EntityVersion::counter, EntityVersion::deviceId)
}

@Serializable
enum class SyncEntityType {
    CATEGORY,
    TASK,
    TASK_SUBTASK,
    HABIT,
    HABIT_RECORD,
    HABIT_SCHEDULE_ITEM,
    CAPTURE_ITEM,
    MEMORY_FRAGMENT,
    USER_PROFILE,
}

@Serializable
data class EntityTombstone(
    val entityType: SyncEntityType,
    val entityUid: String,
    val version: EntityVersion,
    val deletedAt: String,
)

@Serializable
enum class ChangeOrigin {
    LOCAL_USER,
    LOCAL_MIGRATION,
    REMOTE_SYNC,
    SYSTEM_REPAIR,
}

@Serializable
data class ChangeSet(
    val transactionId: String,
    val origin: ChangeOrigin,
    val entityTypes: Set<SyncEntityType>,
    val entityUids: Set<String>,
)
