package com.deadliner.platform

import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlinx.datetime.DayOfWeek

/**
 * iOS NotificationService actual 实现。
 *
 * 使用 UNUserNotificationCenter 实现通知调度。
 */
actual class NotificationService {
    private val notificationCenter = UNUserNotificationCenter.currentNotificationCenter()

    // ===== 任务通知 =====

    actual fun setTaskNotification(config: TaskNotificationConfig) {
        val content = UNMutableNotificationContent().apply {
            setTitle(config.title)
            setBody("还有 ${config.reminderMinutes} 分钟截止")
            setSound(defaultSound())
        }

        // 计算触发时间（简化实现，实际需要计算 dueAt - reminderMinutes）
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = config.reminderMinutes.toLong() * 60,
            repeats = false,
        )

        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "TASK_${config.taskUid}",
            content = content,
            trigger = trigger,
        )

        notificationCenter.addNotificationRequest(request) { error ->
            error?.let { println("Failed to schedule task notification: $it") }
        }
    }

    actual fun cancelTaskNotification(taskUid: String) {
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(
            identifiers = listOf("TASK_$taskUid"),
        )
    }

    actual fun cancelAllTaskNotifications() {
        // 获取所有待处理的通知，过滤并取消任务相关通知
        notificationCenter.getPendingNotificationRequestsWithCompletionHandler { requests ->
            val taskIdentifiers = requests
                ?.filterIsInstance<UNNotificationRequest>()
                ?.filter { it.identifier.startsWith("TASK_") }
                ?.map { it.identifier }
                ?: emptyList()
            notificationCenter.removePendingNotificationRequestsWithIdentifiers(taskIdentifiers)
        }
    }

    // ===== 习惯通知 =====

    actual fun setHabitNotification(config: HabitNotificationConfig) {
        val content = UNMutableNotificationContent().apply {
            setTitle("习惯提醒")
            setBody("该完成 ${config.name} 了")
            setSound(defaultSound())
        }

        // 解析 alarmTime (HH:mm)
        val parts = config.alarmTime.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        // 计算到下一个提醒时间的秒数（简化实现）
        val now = kotlinx.datetime.Clock.System.now()
        val today = kotlinx.datetime.TimeZone.currentSystemDefault()
        val targetTime = now // 实际需要计算到下一个 hour:minute 的时间
        val intervalSeconds = 24 * 60 * 60L // 每天

        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = intervalSeconds,
            repeats = true,
        )

        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "HABIT_${config.habitUid}",
            content = content,
            trigger = trigger,
        )

        notificationCenter.addNotificationRequest(request) { error ->
            error?.let { println("Failed to schedule habit notification: $it") }
        }
    }

    actual fun cancelHabitNotification(habitUid: String) {
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(
            identifiers = listOf("HABIT_$habitUid"),
        )
    }

    actual fun cancelAllHabitNotifications() {
        notificationCenter.getPendingNotificationRequestsWithCompletionHandler { requests ->
            val habitIdentifiers = requests
                ?.filterIsInstance<UNNotificationRequest>()
                ?.filter { it.identifier.startsWith("HABIT_") }
                ?.map { it.identifier }
                ?: emptyList()
            notificationCenter.removePendingNotificationRequestsWithIdentifiers(habitIdentifiers)
        }
    }

    // ===== 每日统计通知 =====

    actual fun setDailySummaryNotification(config: DailySummaryConfig) {
        val content = UNMutableNotificationContent().apply {
            setTitle("每日总结")
            setBody("查看今日任务和习惯完成情况")
            setSound(defaultSound())
        }

        // 解析 time (HH:mm)
        val parts = config.time.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 20
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        // 每天触发
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = 24 * 60 * 60L,
            repeats = true,
        )

        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = "DAILY_SUMMARY",
            content = content,
            trigger = trigger,
        )

        notificationCenter.addNotificationRequest(request) { error ->
            error?.let { println("Failed to schedule daily summary notification: $it") }
        }
    }

    actual fun cancelDailySummaryNotification() {
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(
            identifiers = listOf("DAILY_SUMMARY"),
        )
    }

    // ===== 即将到期实时更新（iOS 不支持，no-op）=====

    actual fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig) {
        // iOS 不支持前台服务实时更新，no-op
    }

    actual fun cancelUpcomingLiveUpdate(taskUid: String) {
        // iOS 不支持前台服务实时更新，no-op
    }

    // ===== 通用 =====

    actual fun cancelAll() {
        notificationCenter.removeAllPendingNotificationRequests()
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

    private fun defaultSound(): platform.UserNotifications.UNNotificationSound {
        return platform.UserNotifications.UNNotificationSound.defaultSound
    }
}
