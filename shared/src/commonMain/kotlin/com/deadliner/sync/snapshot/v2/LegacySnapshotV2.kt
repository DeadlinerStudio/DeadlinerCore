package com.deadliner.sync.snapshot.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire DTOs for the three iOS v2 snapshot files. They intentionally retain their snake_case keys. */
@Serializable
data class LegacySnapshotV2EntityVersion(
    val ts: String,
    val ctr: Int,
    val dev: String,
)

@Serializable
data class LegacySnapshotV2Version(
    val ts: String,
    val dev: String,
)

@Serializable
data class LegacyTaskSnapshotV2Root(
    val version: LegacySnapshotV2Version,
    val items: List<LegacyTaskSnapshotV2Item>,
)

@Serializable
data class LegacyTaskSnapshotV2Item(
    val uid: String,
    val ver: LegacySnapshotV2EntityVersion,
    val deleted: Boolean,
    val doc: LegacyTaskSnapshotV2Doc? = null,
)

@Serializable
data class LegacyTaskSnapshotV2Doc(
    val id: Long,
    val name: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val state: String,
    @SerialName("complete_time") val completeTime: String,
    val note: String,
    @SerialName("is_stared") val isStared: Int,
    val type: String,
    @SerialName("habit_count") val habitCount: Int,
    @SerialName("habit_total_count") val habitTotalCount: Int,
    @SerialName("calendar_event") val calendarEvent: Long,
    val timestamp: String,
    @SerialName("sub_tasks") val subtasks: List<LegacyTaskSnapshotV2Subtask>,
    @SerialName("category_uid") val categoryUid: String? = null,
)

@Serializable
data class LegacyTaskSnapshotV2Subtask(
    val id: String,
    val content: String,
    @SerialName("is_completed") val isCompleted: Int,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class LegacyHabitSnapshotV2Root(
    val version: LegacySnapshotV2Version,
    val items: List<LegacyHabitSnapshotV2Item>,
)

@Serializable
data class LegacyHabitSnapshotV2Item(
    val uid: String,
    val ver: LegacySnapshotV2EntityVersion,
    val deleted: Boolean,
    val doc: LegacyHabitSnapshotV2Doc? = null,
)

@Serializable
data class LegacyHabitSnapshotV2Doc(
    @SerialName("ddl_uid") val ddlUid: String,
    val habit: LegacyHabitSnapshotV2Payload,
    val records: List<LegacyHabitRecordSnapshotV2Payload>,
)

@Serializable
data class LegacyHabitSnapshotV2Payload(
    val name: String,
    val description: String? = null,
    val color: Int? = null,
    @SerialName("icon_key") val iconKey: String? = null,
    @SerialName("category_uid") val categoryUid: String? = null,
    val period: String,
    @SerialName("times_per_period") val timesPerPeriod: Int,
    @SerialName("goal_type") val goalType: String,
    @SerialName("total_target") val totalTarget: Int? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val status: String,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("alarm_time") val alarmTime: String? = null,
)

@Serializable
data class LegacyHabitRecordSnapshotV2Payload(
    val date: String,
    val count: Int,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class LegacyCategorySnapshotV2Root(
    val version: LegacySnapshotV2Version,
    val items: List<LegacyCategorySnapshotV2Item>,
)

@Serializable
data class LegacyCategorySnapshotV2Item(
    val uid: String,
    val ver: LegacySnapshotV2EntityVersion,
    val deleted: Boolean,
    val doc: LegacyCategorySnapshotV2Doc? = null,
)

@Serializable
data class LegacyCategorySnapshotV2Doc(
    val name: String,
    @SerialName("icon_key") val iconKey: String,
    @SerialName("color_hex") val colorHex: String,
    @SerialName("is_preset") val isPreset: Int,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)
