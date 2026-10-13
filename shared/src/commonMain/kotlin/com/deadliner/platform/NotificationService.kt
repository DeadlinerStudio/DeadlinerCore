package com.deadliner.platform

import kotlinx.datetime.DayOfWeek

/**
 * 平台通知服务抽象。
 *
 * 各平台 actual 实现：
 * - iOS: UNUserNotificationCenter
 * - Android: AlarmManager + BroadcastReceiver
 * - HarmonyOS: reminderAgentManager
 */
interface NotificationGateway {
    /** Optional fixed-rule metadata path; legacy native hosts retain their existing methods. */
    fun applyNotificationMetadata(metadata: NotificationMetadata) {}

    // ===== 任务通知 =====

    /** 设置任务到期提醒 */
    fun setTaskNotification(config: TaskNotificationConfig)

    /** 取消特定任务通知 */
    fun cancelTaskNotification(taskUid: String)

    /** 取消所有任务通知 */
    fun cancelAllTaskNotifications()

    // ===== 习惯通知 =====

    /** 设置习惯每日提醒 */
    fun setHabitNotification(config: HabitNotificationConfig)

    /** 取消特定习惯通知 */
    fun cancelHabitNotification(habitUid: String)

    /** 取消所有习惯通知 */
    fun cancelAllHabitNotifications()

    // ===== 每日统计通知 =====

    /** 设置每日统计摘要通知 */
    fun setDailySummaryNotification(config: DailySummaryConfig)

    /** 取消每日统计通知 */
    fun cancelDailySummaryNotification()

    // ===== 即将到期实时更新（Android 特有） =====

    /** 设置即将到期的实时倒计时通知 */
    fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig)

    /** 取消即将到期实时更新 */
    fun cancelUpcomingLiveUpdate(taskUid: String)

    // ===== 通用 =====

    /** 取消所有通知 */
    fun cancelAll()

    /** 刷新所有通知（批量重建） */
    fun refreshAll(tasks: List<TaskNotificationInfo>, habits: List<HabitNotificationInfo>)
}

/**
 * Native notification bridge retained as the public platform entry point.
 *
 * Business services depend on [NotificationGateway] so their behavior is testable without a
 * system notification center, alarm manager, or HarmonyOS NAPI bridge.
 */
expect class NotificationService : NotificationGateway {
    override fun setTaskNotification(config: TaskNotificationConfig)
    override fun cancelTaskNotification(taskUid: String)
    override fun cancelAllTaskNotifications()
    override fun setHabitNotification(config: HabitNotificationConfig)
    override fun cancelHabitNotification(habitUid: String)
    override fun cancelAllHabitNotifications()
    override fun setDailySummaryNotification(config: DailySummaryConfig)
    override fun cancelDailySummaryNotification()
    override fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig)
    override fun cancelUpcomingLiveUpdate(taskUid: String)
    override fun cancelAll()
    override fun refreshAll(tasks: List<TaskNotificationInfo>, habits: List<HabitNotificationInfo>)
}

// ===== 配置对象（易于扩展新参数）=====

/** 任务通知配置 */
data class TaskNotificationConfig(
    val taskUid: String,
    val title: String,
    val dueAt: String,
    val reminderMinutes: Int = 60,  // 提前 N 分钟提醒
    val priority: NotificationPriority = NotificationPriority.NORMAL,
)

/** 习惯通知配置 */
data class HabitNotificationConfig(
    val habitUid: String,
    val name: String,
    val alarmTime: String,  // HH:mm 格式
    val daysOfWeek: List<DayOfWeek> = DayOfWeek.entries,
    /** Concrete local occurrences derived by KMP; platform code must not reimplement recurrence. */
    val scheduledLocalDateTimes: List<String> = emptyList(),
    val isScheduleAuthoritative: Boolean = false,
)

/** 每日统计配置 */
data class DailySummaryConfig(
    val time: String,  // HH:mm 格式
    val showTaskCount: Boolean = true,
    val showHabitProgress: Boolean = true,
)

/** 即将到期实时更新配置（Android 特有） */
data class UpcomingLiveUpdateConfig(
    val taskUid: String,
    val title: String,
    val dueAt: String,
    val advanceMinutes: Int = 10,
    val updateIntervalSeconds: Int = 30,  // 更新间隔
) {
    init {
        require(advanceMinutes >= 0) { "advanceMinutes must not be negative" }
        require(updateIntervalSeconds > 0) { "updateIntervalSeconds must be positive" }
    }
}

/** 通知优先级（密封类，编译时类型安全） */
enum class NotificationPriority {
    LOW, NORMAL, HIGH, URGENT
}

// ===== 通知信息（用于批量刷新）=====

/** 任务通知信息 */
data class TaskNotificationInfo(
    val uid: String,
    val title: String,
    val dueAt: String?,
    val state: String,
)

/** 习惯通知信息 */
data class HabitNotificationInfo(
    val uid: String,
    val name: String,
    val alarmTime: String?,
    val status: String,
)
