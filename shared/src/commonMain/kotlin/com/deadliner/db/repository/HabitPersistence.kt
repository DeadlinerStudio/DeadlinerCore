package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitGoalWindow
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitTracking
import com.deadliner.domain.habit.HabitTrackingMode

internal fun AppDatabase.upsertHabit(habit: Habit, deleted: Boolean = habit.isDeleted) {
    ensureCategoryReference(habit.categoryUid, habit.updatedAt)
    habitQueries.insertHabit(
        habit.uid, habit.name, habit.description, habit.color?.toLong(), habit.iconKey, habit.categoryUid,
        habit.schedule.kind.name, habit.schedule.startOn, habit.schedule.endOn,
        habit.schedule.weekdaysMask?.toLong(), habit.schedule.algorithmKey, habit.goal.window.name,
        habit.goal.targetCount.toLong(), habit.tracking.mode.name, habit.tracking.unit,
        habit.tracking.defaultIncrement.toLong(), habit.status.name, habit.sortOrder.toLong(), habit.reminder?.localTime,
        habit.reminder?.isEnabled?.asLong(), habit.createdAt, habit.updatedAt, deleted.asLong(),
    )
    habitQueries.updateHabit(
        habit.name, habit.description, habit.color?.toLong(), habit.iconKey, habit.categoryUid,
        habit.schedule.kind.name, habit.schedule.startOn, habit.schedule.endOn,
        habit.schedule.weekdaysMask?.toLong(), habit.schedule.algorithmKey, habit.goal.window.name,
        habit.goal.targetCount.toLong(), habit.tracking.mode.name, habit.tracking.unit,
        habit.tracking.defaultIncrement.toLong(), habit.status.name, habit.sortOrder.toLong(),
        habit.reminder?.localTime, habit.reminder?.isEnabled?.asLong(), habit.updatedAt, deleted.asLong(), habit.uid,
    )
}

@Suppress("LongParameterList")
internal fun habitFromSql(
    uid: String,
    name: String,
    description: String?,
    color: Long?,
    iconKey: String?,
    categoryUid: String?,
    scheduleKind: String,
    scheduleStartOn: String,
    scheduleEndOn: String?,
    scheduleWeekdaysMask: Long?,
    scheduleAlgorithmKey: String?,
    goalWindow: String,
    goalTargetCount: Long,
    trackingMode: String,
    trackingUnit: String?,
    trackingDefaultIncrement: Long,
    status: String,
    sortOrder: Long,
    reminderLocalTime: String?,
    reminderEnabled: Long?,
    createdAt: String,
    updatedAt: String,
    isDeleted: Long,
) = Habit(
    uid = uid,
    name = name,
    description = description,
    color = color?.toInt(),
    iconKey = iconKey,
    categoryUid = categoryUid,
    schedule = HabitSchedule(
        kind = HabitScheduleKind.valueOf(scheduleKind),
        startOn = scheduleStartOn,
        endOn = scheduleEndOn,
        weekdaysMask = scheduleWeekdaysMask?.toInt(),
        algorithmKey = scheduleAlgorithmKey,
    ),
    goal = HabitGoal(HabitGoalWindow.valueOf(goalWindow), goalTargetCount.toInt()),
    tracking = HabitTracking(HabitTrackingMode.valueOf(trackingMode), trackingUnit, trackingDefaultIncrement.toInt()),
    status = HabitStatus.valueOf(status),
    sortOrder = sortOrder.toInt(),
    reminder = reminderLocalTime?.let { HabitReminder(it, reminderEnabled?.asBoolean() ?: true) },
    createdAt = createdAt,
    updatedAt = updatedAt,
    isDeleted = isDeleted.asBoolean(),
)
