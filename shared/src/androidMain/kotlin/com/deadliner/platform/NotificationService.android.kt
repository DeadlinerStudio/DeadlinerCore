package com.deadliner.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Android scheduling boundary. Notification content and actions remain app-owned. */
actual class NotificationService(private val context: Context) : NotificationGateway {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    actual override fun setTaskNotification(config: TaskNotificationConfig) {
        cancelTaskNotification(config.taskUid)
        val dueMillis = parseDateTime(config.dueAt)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: return
        val triggerMillis = dueMillis - config.reminderMinutes * 60_000L
        if (triggerMillis <= System.currentTimeMillis()) return
        val intent = Intent(ACTION_TASK).setPackage(context.packageName)
            .putExtra(EXTRA_UID, config.taskUid)
            .putExtra(EXTRA_TITLE, config.title)
        schedule(config.taskUid, TASK_REQUEST_OFFSET, triggerMillis, intent)
        remember(TASK_KEYS, config.taskUid)
    }

    actual override fun cancelTaskNotification(taskUid: String) {
        cancel(taskUid, TASK_REQUEST_OFFSET, Intent(ACTION_TASK).setPackage(context.packageName).putExtra(EXTRA_UID, taskUid))
        forget(TASK_KEYS, taskUid)
    }

    actual override fun cancelAllTaskNotifications() = cancelRemembered(TASK_KEYS, ::cancelTaskNotification)

    actual override fun setHabitNotification(config: HabitNotificationConfig) {
        cancelHabitNotification(config.habitUid)
        val time = runCatching { LocalTime.parse(config.alarmTime) }.getOrNull() ?: return
        val now = LocalDateTime.now()
        var trigger = now.toLocalDate().atTime(time)
        if (!trigger.isAfter(now)) trigger = trigger.plusDays(1)
        val intent = Intent(ACTION_HABIT).setPackage(context.packageName)
            .putExtra(EXTRA_UID, config.habitUid)
            .putExtra(EXTRA_TITLE, config.name)
        schedule(
            config.habitUid,
            HABIT_REQUEST_OFFSET,
            trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            intent,
        )
        remember(HABIT_KEYS, config.habitUid)
    }

    actual override fun cancelHabitNotification(habitUid: String) {
        cancel(habitUid, HABIT_REQUEST_OFFSET, Intent(ACTION_HABIT).setPackage(context.packageName).putExtra(EXTRA_UID, habitUid))
        forget(HABIT_KEYS, habitUid)
    }

    actual override fun cancelAllHabitNotifications() = cancelRemembered(HABIT_KEYS, ::cancelHabitNotification)

    actual override fun setDailySummaryNotification(config: DailySummaryConfig) {
        cancelDailySummaryNotification()
        val time = runCatching { LocalTime.parse(config.time) }.getOrNull() ?: return
        val now = LocalDateTime.now()
        var trigger = now.toLocalDate().atTime(time)
        if (!trigger.isAfter(now)) trigger = trigger.plusDays(1)
        schedule(
            DAILY_UID,
            DAILY_REQUEST_OFFSET,
            trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            Intent(ACTION_DAILY).setPackage(context.packageName),
        )
    }

    actual override fun cancelDailySummaryNotification() {
        cancel(DAILY_UID, DAILY_REQUEST_OFFSET, Intent(ACTION_DAILY).setPackage(context.packageName))
    }
    actual override fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig) = Unit
    actual override fun cancelUpcomingLiveUpdate(taskUid: String) = Unit

    actual override fun cancelAll() {
        cancelAllTaskNotifications()
        cancelAllHabitNotifications()
        cancelDailySummaryNotification()
    }

    actual override fun refreshAll(tasks: List<TaskNotificationInfo>, habits: List<HabitNotificationInfo>) {
        cancelAll()
        tasks.filter { it.state.equals("active", true) }.forEach { task ->
            task.dueAt?.let { due -> setTaskNotification(TaskNotificationConfig(task.uid, task.title, due)) }
        }
        habits.filter { it.status.equals("active", true) }.forEach { habit ->
            habit.alarmTime?.let { time -> setHabitNotification(HabitNotificationConfig(habit.uid, habit.name, time)) }
        }
    }

    private fun schedule(uid: String, offset: Int, triggerMillis: Long, intent: Intent) {
        val pending = PendingIntent.getBroadcast(
            context,
            offset + uid.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pending)
        }
    }

    private fun cancel(uid: String, offset: Int, intent: Intent) {
        PendingIntent.getBroadcast(
            context,
            offset + uid.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { pending -> alarmManager.cancel(pending); pending.cancel() }
    }

    private fun remember(key: String, uid: String) {
        preferences.edit().putStringSet(key, preferences.getStringSet(key, emptySet()).orEmpty() + uid).apply()
    }

    private fun forget(key: String, uid: String) {
        preferences.edit().putStringSet(key, preferences.getStringSet(key, emptySet()).orEmpty() - uid).apply()
    }

    private fun cancelRemembered(key: String, cancelOne: (String) -> Unit) {
        preferences.getStringSet(key, emptySet()).orEmpty().toList().forEach(cancelOne)
    }

    private fun parseDateTime(raw: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(raw) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(raw).toLocalDateTime() }.getOrNull()

    companion object {
        const val ACTION_TASK = "com.deadliner.kmp.notification.TASK"
        const val ACTION_HABIT = "com.deadliner.kmp.notification.HABIT"
        const val ACTION_DAILY = "com.deadliner.kmp.notification.DAILY"
        const val EXTRA_UID = "com.deadliner.kmp.notification.UID"
        const val EXTRA_TITLE = "com.deadliner.kmp.notification.TITLE"
        private const val PREFERENCES = "kmp_notification_schedule"
        private const val TASK_KEYS = "tasks"
        private const val HABIT_KEYS = "habits"
        private const val TASK_REQUEST_OFFSET = 210_000
        private const val HABIT_REQUEST_OFFSET = 310_000
        private const val DAILY_REQUEST_OFFSET = 410_000
        private const val DAILY_UID = "daily-summary"
    }
}
