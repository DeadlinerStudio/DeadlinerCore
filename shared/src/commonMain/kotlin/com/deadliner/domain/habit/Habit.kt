package com.deadliner.domain.habit

import kotlinx.serialization.Serializable

@Serializable
data class Habit(
    val uid: String,
    val name: String,
    val description: String? = null,
    val color: Int? = null,
    val iconKey: String? = null,
    val categoryUid: String? = null,
    val schedule: HabitSchedule,
    val goal: HabitGoal,
    val tracking: HabitTracking = HabitTracking(),
    val status: HabitStatus = HabitStatus.ACTIVE,
    val sortOrder: Int = 0,
    val reminder: HabitReminder? = null,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val isDeleted: Boolean = false,
) {
    @Deprecated("Use schedule, goal and tracking")
    constructor(
        uid: String,
        name: String,
        description: String? = null,
        color: Int? = null,
        iconKey: String? = null,
        categoryUid: String? = null,
        period: HabitPeriod,
        timesPerPeriod: Int = 1,
        goalType: HabitGoalType = HabitGoalType.PER_PERIOD,
        totalTarget: Int? = null,
        status: HabitStatus = HabitStatus.ACTIVE,
        sortOrder: Int = 0,
        reminder: HabitReminder? = null,
        createdAt: String,
        updatedAt: String = createdAt,
        isDeleted: Boolean = false,
    ) : this(
        uid = uid,
        name = name,
        description = description,
        color = color,
        iconKey = iconKey,
        categoryUid = categoryUid,
        schedule = period.toSchedule(createdAt.take(10)),
        goal = HabitGoal(
            window = if (goalType == HabitGoalType.TOTAL) HabitGoalWindow.LIFETIME else period.toGoalWindow(),
            targetCount = (if (goalType == HabitGoalType.TOTAL) totalTarget else timesPerPeriod)?.coerceAtLeast(1) ?: 1,
        ),
        tracking = HabitTracking(
            mode = if ((if (goalType == HabitGoalType.TOTAL) totalTarget else timesPerPeriod) == 1) {
                HabitTrackingMode.CHECK_OFF
            } else {
                HabitTrackingMode.QUANTITY
            },
        ),
        status = status,
        sortOrder = sortOrder,
        reminder = reminder,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDeleted = isDeleted,
    )

    @Deprecated("Use schedule and goal")
    val period: HabitPeriod
        get() = when {
            schedule.kind == HabitScheduleKind.ONCE -> HabitPeriod.ONCE
            schedule.algorithmKey == HabitAlgorithms.EBBINGHAUS_CLASSIC_V1 -> HabitPeriod.EBBINGHAUS
            goal.window == HabitGoalWindow.WEEK -> HabitPeriod.WEEKLY
            goal.window == HabitGoalWindow.MONTH -> HabitPeriod.MONTHLY
            else -> HabitPeriod.DAILY
        }

    @Deprecated("Use goal.targetCount")
    val timesPerPeriod: Int get() = goal.targetCount

    @Deprecated("Use goal.window")
    val goalType: HabitGoalType get() = if (goal.window == HabitGoalWindow.LIFETIME) HabitGoalType.TOTAL else HabitGoalType.PER_PERIOD

    @Deprecated("Use goal.targetCount")
    val totalTarget: Int? get() = goal.targetCount.takeIf { goal.window == HabitGoalWindow.LIFETIME }
}

@Deprecated("Migration adapter only")
@Serializable
enum class HabitPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
    ONCE,
    EBBINGHAUS,
}

@Deprecated("Migration adapter only")
@Serializable
enum class HabitGoalType {
    PER_PERIOD,
    TOTAL,
}

private fun HabitPeriod.toSchedule(startOn: String): HabitSchedule = when (this) {
    HabitPeriod.ONCE -> HabitSchedule(HabitScheduleKind.ONCE, startOn)
    HabitPeriod.EBBINGHAUS -> HabitSchedule(
        HabitScheduleKind.ALGORITHM,
        startOn,
        algorithmKey = HabitAlgorithms.EBBINGHAUS_CLASSIC_V1,
    )
    else -> HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, startOn, weekdaysMask = HabitWeekdays.ALL)
}

private fun HabitPeriod.toGoalWindow(): HabitGoalWindow = when (this) {
    HabitPeriod.WEEKLY -> HabitGoalWindow.WEEK
    HabitPeriod.MONTHLY -> HabitGoalWindow.MONTH
    HabitPeriod.DAILY, HabitPeriod.ONCE, HabitPeriod.EBBINGHAUS -> HabitGoalWindow.SCHEDULED_DAY
}

@Serializable
enum class HabitStatus {
    ACTIVE,
    ARCHIVED,
}

@Serializable
enum class HabitStatusAction { ARCHIVE, RESTORE }

@Serializable
enum class HabitActionOutcome {
    APPLIED,
    NO_OP,
    NOT_FOUND,
    NOT_DUE,
    FUTURE_DATE,
    INACTIVE,
    INVALID_AMOUNT,
}
