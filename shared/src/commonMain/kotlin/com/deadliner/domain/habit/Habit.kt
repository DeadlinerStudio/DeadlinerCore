package com.deadliner.domain.habit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Habit(
    val uid: String,
    val name: String,
    val description: String? = null,
    val color: Int? = null,
    val iconKey: String? = null,
    val categoryUid: String? = null,
    val period: HabitPeriod,
    val timesPerPeriod: Int = 1,
    val goalType: HabitGoalType = HabitGoalType.PER_PERIOD,
    val totalTarget: Int? = null,
    val status: HabitStatus = HabitStatus.ACTIVE,
    val sortOrder: Int = 0,
    val reminder: HabitReminder? = null,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val isDeleted: Boolean = false,
)

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
)

@Serializable
data class HabitReminder(
    val localTime: String,
    val isEnabled: Boolean = true,
)

@Serializable
data class HabitScheduleItem(
    val uid: String,
    val habitUid: String,
    val dueAt: String,
    val state: HabitScheduleState = HabitScheduleState.PENDING,
    val createdAt: String,
    val updatedAt: String,
    val isDeleted: Boolean = false,
)

@Serializable
enum class HabitPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
    ONCE,
    EBBINGHAUS,
}

@Serializable
enum class HabitGoalType {
    PER_PERIOD,
    TOTAL,
}

@Serializable
enum class HabitStatus {
    ACTIVE,
    ARCHIVED,
}

@Serializable
enum class HabitRecordStatus {
    COMPLETED,
    SKIPPED,
    FAILED,
}

@Serializable
enum class HabitScheduleState {
    PENDING,
    COMPLETED,
    SKIPPED,
}
