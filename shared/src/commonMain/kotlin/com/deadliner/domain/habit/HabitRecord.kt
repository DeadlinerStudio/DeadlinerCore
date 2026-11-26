package com.deadliner.domain.habit

import kotlinx.serialization.Serializable

@Serializable
data class HabitRecord(
    val uid: String,
    val habitUid: String,
    val occurredOn: String,
    val count: Int = 1,
    val status: HabitRecordStatus = HabitRecordStatus.COMPLETED,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val isDeleted: Boolean = false,
) {
    init {
        require(count > 0) { "Habit record count must be positive" }
    }
}

@Serializable
data class HabitReminder(
    val localTime: String,
    val isEnabled: Boolean = true,
)

@Serializable
enum class HabitRecordStatus {
    COMPLETED,
    SKIPPED,
    FAILED,
}
