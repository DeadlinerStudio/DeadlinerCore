@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.deadliner.platform

import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import platform.Foundation.NSDateComponents

/**
 * iOS NotificationService actual 实现。
 *
 * 使用 UNUserNotificationCenter 实现通知调度。
 */
actual class NotificationService : NotificationGateway {
    private val notificationCenter = UNUserNotificationCenter.currentNotificationCenter()

    // ===== 任务通知 =====

    actual override fun setTaskNotification(config: TaskNotificationConfig) {
        val content = UNMutableNotificationContent().apply {
            setTitle(config.title)
            setBody("还有 ${config.reminderMinutes} 分钟截止")
            setSound(defaultSound())
        }

        // 计算触发时间（简化实现，实际需要计算 dueAt - reminderMinutes）
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = config.reminderMinutes.toDouble() * 60.0,
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

    actual override fun cancelTaskNotification(taskUid: String) {
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(
            identifiers = listOf("TASK_$taskUid"),
        )
    }

    actual override fun cancelAllTaskNotifications() {
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

    actual override fun setHabitNotification(config: HabitNotificationConfig) {
        notificationCenter.getPendingNotificationRequestsWithCompletionHandler { requests ->
            val staleIdentifiers = requests
                ?.filterIsInstance<UNNotificationRequest>()
                ?.filter { it.identifier.startsWith("HABIT_${config.habitUid}_") || it.identifier == "HABIT_${config.habitUid}" }
                ?.map { it.identifier }
                ?: emptyList()
            notificationCenter.removePendingNotificationRequestsWithIdentifiers(staleIdentifiers)

            if (!config.isScheduleAuthoritative) return@getPendingNotificationRequestsWithCompletionHandler
            config.scheduledLocalDateTimes.forEach { rawDateTime ->
                val dateTime = runCatching { LocalDateTime.parse(rawDateTime) }.getOrNull()
                    ?: return@forEach
                val content = UNMutableNotificationContent().apply {
                    setTitle("习惯提醒")
                    setBody("该完成 ${config.name} 了")
                    setSound(defaultSound())
                }
                val components = NSDateComponents().apply {
                    year = dateTime.year.toLong()
                    month = dateTime.monthNumber.toLong()
                    day = dateTime.dayOfMonth.toLong()
                    hour = dateTime.hour.toLong()
                    minute = dateTime.minute.toLong()
                }
                val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
                    dateComponents = components,
                    repeats = false,
                )
                val request = UNNotificationRequest.requestWithIdentifier(
                    identifier = "HABIT_${config.habitUid}_${dateTime.date}",
                    content = content,
                    trigger = trigger,
                )
                notificationCenter.addNotificationRequest(request) { error ->
                    error?.let { println("Failed to schedule habit notification: $it") }
                }
            }
        }
    }

    actual override fun cancelHabitNotification(habitUid: String) {
        notificationCenter.getPendingNotificationRequestsWithCompletionHandler { requests ->
            val identifiers = requests
                ?.filterIsInstance<UNNotificationRequest>()
                ?.filter { it.identifier.startsWith("HABIT_${habitUid}_") || it.identifier == "HABIT_$habitUid" }
                ?.map { it.identifier }
                ?: emptyList()
            notificationCenter.removePendingNotificationRequestsWithIdentifiers(identifiers)
        }
    }

    actual override fun cancelAllHabitNotifications() {
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

    actual override fun setDailySummaryNotification(config: DailySummaryConfig) {
        val content = UNMutableNotificationContent().apply {
            setTitle("每日总结")
            setBody("查看今日任务和习惯完成情况")
            setSound(defaultSound())
        }

        // 每天触发
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = 24.0 * 60.0 * 60.0,
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

    actual override fun cancelDailySummaryNotification() {
        notificationCenter.removePendingNotificationRequestsWithIdentifiers(
            identifiers = listOf("DAILY_SUMMARY"),
        )
    }

    // ===== 即将到期实时更新（iOS 不支持，no-op）=====

    actual override fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig) {
        // iOS 不支持前台服务实时更新，no-op
    }

    actual override fun cancelUpcomingLiveUpdate(taskUid: String) {
        // iOS 不支持前台服务实时更新，no-op
    }

    // ===== 通用 =====

    actual override fun cancelAll() {
        notificationCenter.removeAllPendingNotificationRequests()
    }

    actual override fun refreshAll(tasks: List<TaskNotificationInfo>, habits: List<HabitNotificationInfo>) {
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
