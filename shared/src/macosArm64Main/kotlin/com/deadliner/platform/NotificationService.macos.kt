package com.deadliner.platform

/**
 * The macOS target exists for local database tooling and integration tests, not as a product host.
 * Platform notifications must therefore fail fast instead of pretending that work was scheduled.
 */
actual class NotificationService {
    actual fun setTaskNotification(config: TaskNotificationConfig): Unit = unsupported()
    actual fun cancelTaskNotification(taskUid: String): Unit = unsupported()
    actual fun cancelAllTaskNotifications(): Unit = unsupported()
    actual fun setHabitNotification(config: HabitNotificationConfig): Unit = unsupported()
    actual fun cancelHabitNotification(habitUid: String): Unit = unsupported()
    actual fun cancelAllHabitNotifications(): Unit = unsupported()
    actual fun setDailySummaryNotification(config: DailySummaryConfig): Unit = unsupported()
    actual fun cancelDailySummaryNotification(): Unit = unsupported()
    actual fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig): Unit = unsupported()
    actual fun cancelUpcomingLiveUpdate(taskUid: String): Unit = unsupported()
    actual fun cancelAll(): Unit = unsupported()
    actual fun refreshAll(tasks: List<TaskNotificationInfo>, habits: List<HabitNotificationInfo>): Unit = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "NotificationService is unavailable in the macOS development target.",
    )
}
