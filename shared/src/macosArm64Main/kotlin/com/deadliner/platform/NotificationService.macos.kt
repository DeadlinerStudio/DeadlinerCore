package com.deadliner.platform

/**
 * The macOS target exists for local database tooling and integration tests, not as a product host.
 * Platform notifications must therefore fail fast instead of pretending that work was scheduled.
 */
actual class NotificationService : NotificationGateway {
    actual override fun setTaskNotification(config: TaskNotificationConfig): Unit = unsupported()
    actual override fun cancelTaskNotification(taskUid: String): Unit = unsupported()
    actual override fun cancelAllTaskNotifications(): Unit = unsupported()
    actual override fun setHabitNotification(config: HabitNotificationConfig): Unit = unsupported()
    actual override fun cancelHabitNotification(habitUid: String): Unit = unsupported()
    actual override fun cancelAllHabitNotifications(): Unit = unsupported()
    actual override fun setDailySummaryNotification(config: DailySummaryConfig): Unit = unsupported()
    actual override fun cancelDailySummaryNotification(): Unit = unsupported()
    actual override fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig): Unit = unsupported()
    actual override fun cancelUpcomingLiveUpdate(taskUid: String): Unit = unsupported()
    actual override fun cancelAll(): Unit = unsupported()
    actual override fun refreshAll(tasks: List<TaskNotificationInfo>, habits: List<HabitNotificationInfo>): Unit = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "NotificationService is unavailable in the macOS development target.",
    )
}
