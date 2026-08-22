package com.deadliner.service

import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitScheduleEngine
import com.deadliner.domain.habit.HabitProgressEngine
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.platform.HabitNotificationConfig
import com.deadliner.platform.NotificationGateway
import com.deadliner.platform.NotificationPriority
import com.deadliner.platform.TaskNotificationConfig
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Shared authority for deciding which persisted aggregates own a platform notification.
 *
 * Platform actuals only schedule or cancel requests. This class owns the lifecycle rule so the
 * result is identical for Android, iOS and HarmonyOS.
 */
class NotificationOrchestrator(
    private val notifications: NotificationGateway,
    private val taskReminderMinutes: Int = DEFAULT_TASK_REMINDER_MINUTES,
    private val todayProvider: () -> LocalDate = {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    },
) {
    init {
        require(taskReminderMinutes >= 0) { "taskReminderMinutes must not be negative" }
    }

    fun onTaskCreated(task: Task) = reconcileTask(task)

    fun onTaskUpdated(task: Task) = reconcileTask(task)

    fun onTaskDeleted(taskUid: String) {
        notifications.cancelTaskNotification(taskUid)
    }

    fun onHabitCreated(habit: Habit) = reconcileHabit(habit)

    fun onHabitUpdated(habit: Habit) = reconcileHabit(habit)

    fun onHabitRecordsChanged(habit: Habit, records: List<HabitRecord>) = reconcileHabit(habit, records)

    fun onHabitDeleted(habitUid: String) {
        notifications.cancelHabitNotification(habitUid)
    }

    /** Rebuilds platform state from persisted aggregates, discarding stale platform requests. */
    fun refreshAll(tasks: List<Task>, habits: List<Habit>) {
        notifications.cancelAll()
        tasks.forEach(::reconcileTask)
        habits.forEach(::reconcileHabit)
    }

    private fun reconcileTask(task: Task) {
        val dueAt = task.dueAt
        if (task.isDeleted || task.state != TaskState.ACTIVE || dueAt == null) {
            notifications.cancelTaskNotification(task.uid)
            return
        }
        notifications.setTaskNotification(
            TaskNotificationConfig(
                taskUid = task.uid,
                title = task.title,
                dueAt = dueAt,
                reminderMinutes = taskReminderMinutes,
                priority = if (task.isStarred) NotificationPriority.HIGH else NotificationPriority.NORMAL,
            ),
        )
    }

    private fun reconcileHabit(habit: Habit, records: List<HabitRecord> = emptyList()) {
        val reminder = habit.reminder
        if (habit.isDeleted || habit.status != HabitStatus.ACTIVE || reminder == null || !reminder.isEnabled) {
            notifications.cancelHabitNotification(habit.uid)
            return
        }
        notifications.setHabitNotification(
            HabitNotificationConfig(
                habitUid = habit.uid,
                name = habit.name,
                alarmTime = reminder.localTime,
                scheduledLocalDateTimes = HabitReminderPlanner.plan(habit, records, todayProvider()),
                isScheduleAuthoritative = true,
            ),
        )
    }

    private companion object {
        const val DEFAULT_TASK_REMINDER_MINUTES = 60
    }
}

object HabitReminderPlanner {
    private const val HORIZON_DAYS = 64

    fun plan(
        habit: Habit,
        records: List<HabitRecord>,
        from: LocalDate,
        horizonDays: Int = HORIZON_DAYS,
    ): List<String> {
        val reminder = habit.reminder?.takeIf { it.isEnabled } ?: return emptyList()
        if (habit.isDeleted || habit.status != HabitStatus.ACTIVE || horizonDays < 0) return emptyList()
        val through = from.plus(DatePeriod(days = horizonDays))
        return HabitScheduleEngine.dueDates(habit.schedule, from, through)
            .filterNot { date -> HabitProgressEngine.progress(habit, records, date).isComplete }
            .map { date -> "${date}T${reminder.localTime}" }
    }
}
