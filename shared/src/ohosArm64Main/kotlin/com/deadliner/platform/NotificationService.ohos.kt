package com.deadliner.platform

import kotlinx.datetime.DayOfWeek

/**
 * HarmonyOS NotificationService actual 实现。
 *
 * 使用 reminderAgentManager 实现通知调度。
 *
 * 注意：HarmonyOS 的 reminderAgentManager 是系统 API，需要系统签名权限。
 * 在实际实现中，需要通过 bridge 层调用原生 API。
 */
actual class NotificationService : NotificationGateway {
    // ===== 任务通知 =====

    actual override fun setTaskNotification(config: TaskNotificationConfig) {
        // 使用 reminderAgentManager 的 Timer 类型
        // 在实际实现中，需要通过 bridge 层调用原生 API
        println("[HarmonyOS] setTaskNotification: ${config.taskUid}")
    }

    actual override fun cancelTaskNotification(taskUid: String) {
        println("[HarmonyOS] cancelTaskNotification: $taskUid")
    }

    actual override fun cancelAllTaskNotifications() {
        println("[HarmonyOS] cancelAllTaskNotifications")
    }

    // ===== 习惯通知 =====

    actual override fun setHabitNotification(config: HabitNotificationConfig) {
        // 使用 reminderAgentManager 的 Alarm 类型（循环提醒）
        println("[HarmonyOS] setHabitNotification: ${config.habitUid}")
    }

    actual override fun cancelHabitNotification(habitUid: String) {
        println("[HarmonyOS] cancelHabitNotification: $habitUid")
    }

    actual override fun cancelAllHabitNotifications() {
        println("[HarmonyOS] cancelAllHabitNotifications")
    }

    // ===== 每日统计通知 =====

    actual override fun setDailySummaryNotification(config: DailySummaryConfig) {
        // 使用 reminderAgentManager 的 Alarm 类型（循环提醒）
        println("[HarmonyOS] setDailySummaryNotification")
    }

    actual override fun cancelDailySummaryNotification() {
        println("[HarmonyOS] cancelDailySummaryNotification")
    }

    // ===== 即将到期实时更新（HarmonyOS 不支持，no-op）=====

    actual override fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig) {
        // HarmonyOS 不支持前台服务实时更新，no-op
    }

    actual override fun cancelUpcomingLiveUpdate(taskUid: String) {
        // HarmonyOS 不支持前台服务实时更新，no-op
    }

    // ===== 通用 =====

    actual override fun cancelAll() {
        println("[HarmonyOS] cancelAll")
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
}
