package com.deadliner.platform

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.datetime.DayOfWeek

/**
 * Android NotificationService actual 实现。
 *
 * 使用 AlarmManager + NotificationManager 实现通知调度。
 */
actual class NotificationService(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    // ===== 任务通知 =====

    actual fun setTaskNotification(config: TaskNotificationConfig) {
        val intent = createTaskIntent(config.taskUid)
        val pendingIntent = createPendingIntent("TASK_${config.taskUid}", intent)

        // 计算触发时间（简化实现）
        val triggerTime = System.currentTimeMillis() + config.reminderMinutes * 60 * 1000

        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerTime, pendingIntent),
            pendingIntent,
        )
    }

    actual fun cancelTaskNotification(taskUid: String) {
        val intent = createTaskIntent(taskUid)
        val pendingIntent = createPendingIntent("TASK_$taskUid", intent)
        alarmManager.cancel(pendingIntent)
    }

    actual fun cancelAllTaskNotifications() {
        // Android 无法直接取消所有任务通知，需要逐个取消
        // 简化实现：no-op
    }

    // ===== 习惯通知 =====

    actual fun setHabitNotification(config: HabitNotificationConfig) {
        val intent = createHabitIntent(config.habitUid)
        val pendingIntent = createPendingIntent("HABIT_${config.habitUid}", intent)

        // 解析 alarmTime (HH:mm)
        val parts = config.alarmTime.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        // 计算到下一个提醒时间的毫秒数（简化实现）
        val now = System.currentTimeMillis()
        val triggerTime = now + 24 * 60 * 60 * 1000L // 每天

        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerTime, pendingIntent),
            pendingIntent,
        )
    }

    actual fun cancelHabitNotification(habitUid: String) {
        val intent = createHabitIntent(habitUid)
        val pendingIntent = createPendingIntent("HABIT_$habitUid", intent)
        alarmManager.cancel(pendingIntent)
    }

    actual fun cancelAllHabitNotifications() {
        // Android 无法直接取消所有习惯通知，需要逐个取消
        // 简化实现：no-op
    }

    // ===== 每日统计通知 =====

    actual fun setDailySummaryNotification(config: DailySummaryConfig) {
        val intent = createDailySummaryIntent()
        val pendingIntent = createPendingIntent("DAILY_SUMMARY", intent)

        // 解析 time (HH:mm)
        val parts = config.time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 20
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        // 每天触发
        val now = System.currentTimeMillis()
        val triggerTime = now + 24 * 60 * 60 * 1000L

        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerTime, pendingIntent),
            pendingIntent,
        )
    }

    actual fun cancelDailySummaryNotification() {
        val intent = createDailySummaryIntent()
        val pendingIntent = createPendingIntent("DAILY_SUMMARY", intent)
        alarmManager.cancel(pendingIntent)
    }

    // ===== 即将到期实时更新 =====

    actual fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig) {
        // Android 支持前台服务实时更新
        // 简化实现：使用普通通知
        val intent = createTaskIntent(config.taskUid)
        val pendingIntent = createPendingIntent("UPCOMING_${config.taskUid}", intent)

        val triggerTime = System.currentTimeMillis() + config.updateIntervalSeconds * 1000L

        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerTime, pendingIntent),
            pendingIntent,
        )
    }

    actual fun cancelUpcomingLiveUpdate(taskUid: String) {
        val intent = createTaskIntent(taskUid)
        val pendingIntent = createPendingIntent("UPCOMING_$taskUid", intent)
        alarmManager.cancel(pendingIntent)
    }

    // ===== 通用 =====

    actual fun cancelAll() {
        alarmManager.cancelAll()
    }

    actual fun refreshAll(tasks: List<TaskNotificationInfo>, habits: List<HabitNotificationInfo>) {
        // 先取消所有通知
        cancelAll()

        // 重新调度任务通知
        tasks.filter { it.state == "active" && it.dueAt != null }.forEach { task ->
            setTaskNotification(
                TaskNotificationConfig(
                    taskUid = task.uid,
                    title = task.title,
                    dueAt = task.dueAt!!,
                )
            )
        }

        // 重新调度习惯通知
        habits.filter { it.status == "active" && it.alarmTime != null }.forEach { habit ->
            setHabitNotification(
                HabitNotificationConfig(
                    habitUid = habit.uid,
                    name = habit.name,
                    alarmTime = habit.alarmTime!!,
                )
            )
        }
    }

    // ===== 辅助方法 =====

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val taskChannel = NotificationChannel(
                "TASK_CHANNEL",
                "任务提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "任务到期提醒"
            }

            val habitChannel = NotificationChannel(
                "HABIT_CHANNEL",
                "习惯提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "习惯打卡提醒"
            }

            val summaryChannel = NotificationChannel(
                "SUMMARY_CHANNEL",
                "每日总结",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "每日任务总结"
            }

            notificationManager.createNotificationChannel(taskChannel)
            notificationManager.createNotificationChannel(habitChannel)
            notificationManager.createNotificationChannel(summaryChannel)
        }
    }

    private fun createTaskIntent(taskUid: String): Intent {
        // 简化实现：返回空 Intent
        return Intent()
    }

    private fun createHabitIntent(habitUid: String): Intent {
        // 简化实现：返回空 Intent
        return Intent()
    }

    private fun createDailySummaryIntent(): Intent {
        // 简化实现：返回空 Intent
        return Intent()
    }

    private fun createPendingIntent(key: String, intent: Intent): PendingIntent {
        return PendingIntent.getActivity(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
