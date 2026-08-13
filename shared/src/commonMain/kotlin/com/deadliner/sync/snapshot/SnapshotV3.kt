package com.deadliner.sync.snapshot

import com.deadliner.domain.account.UserProfile
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.category.Category
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitScheduleItem
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.sync.EntityVersion
import com.deadliner.domain.task.Task
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The portable full-state snapshot format. Version 3 intentionally replaces the three v2 files
 * with one typed document while retaining a per-entity LWW version and explicit tombstones.
 */
@Serializable
data class SnapshotV3Root(
    @SerialName("format_version")
    val formatVersion: Int = FORMAT_VERSION,
    val version: SnapshotV3Version,
    val categories: List<SnapshotV3CategoryItem> = emptyList(),
    val tasks: List<SnapshotV3TaskItem> = emptyList(),
    val habits: List<SnapshotV3HabitItem> = emptyList(),
    val captures: List<SnapshotV3CaptureItem> = emptyList(),
    @SerialName("memory_fragments")
    val memoryFragments: List<SnapshotV3MemoryFragmentItem> = emptyList(),
    @SerialName("memory_user_profile")
    val memoryUserProfile: SnapshotV3MemoryUserProfileItem? = null,
    @SerialName("user_profiles")
    val userProfiles: List<SnapshotV3UserProfileItem> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION = 3
    }
}

/** Root creation metadata. Entity conflict resolution always uses [SnapshotV3EntityVersion]. */
@Serializable
data class SnapshotV3Version(
    val ts: String,
    val dev: String,
)

@Serializable
data class SnapshotV3EntityVersion(
    val ts: String,
    val ctr: Int,
    val dev: String,
) {
    fun toDomain(): EntityVersion = EntityVersion(timestamp = ts, counter = ctr, deviceId = dev)

    companion object {
        fun fromDomain(version: EntityVersion): SnapshotV3EntityVersion =
            SnapshotV3EntityVersion(ts = version.timestamp, ctr = version.counter, dev = version.deviceId)
    }
}

@Serializable
data class SnapshotV3CategoryItem(
    val uid: String,
    val ver: SnapshotV3EntityVersion,
    val deleted: Boolean,
    val doc: Category? = null,
)

@Serializable
data class SnapshotV3TaskItem(
    val uid: String,
    val ver: SnapshotV3EntityVersion,
    val deleted: Boolean,
    val doc: Task? = null,
)

/** Habit records and schedule items are part of their parent habit aggregate in a full snapshot. */
@Serializable
data class SnapshotV3HabitDocument(
    val habit: Habit,
    val records: List<HabitRecord> = emptyList(),
    @SerialName("schedule_items")
    val scheduleItems: List<HabitScheduleItem> = emptyList(),
)

@Serializable
data class SnapshotV3HabitItem(
    val uid: String,
    val ver: SnapshotV3EntityVersion,
    val deleted: Boolean,
    val doc: SnapshotV3HabitDocument? = null,
)

@Serializable
data class SnapshotV3CaptureItem(
    val uid: String,
    val ver: SnapshotV3EntityVersion,
    val deleted: Boolean,
    val doc: CaptureItem? = null,
)

@Serializable
data class SnapshotV3MemoryFragmentItem(
    val uid: String,
    val ver: SnapshotV3EntityVersion,
    val deleted: Boolean,
    val doc: MemoryFragment? = null,
)

@Serializable
data class SnapshotV3MemoryUserProfileItem(
    val ver: SnapshotV3EntityVersion,
    val content: String,
)

@Serializable
data class SnapshotV3UserProfileItem(
    val uid: String,
    val ver: SnapshotV3EntityVersion,
    val deleted: Boolean,
    val doc: UserProfile? = null,
)
