package com.deadliner.platform

/**
 * 平台日历集成抽象。
 *
 * 各平台 actual 实现：
 * - iOS: EKEventStore
 * - Android: CalendarProvider
 * - HarmonyOS: @ohos.calendar
 */
expect class CalendarPort {
    /** 插入日历事件，返回事件 ID */
    suspend fun insertEvent(config: CalendarEventConfig): Long?

    /** 更新日历事件 */
    suspend fun updateEvent(eventId: Long, config: CalendarEventConfig)

    /** 删除日历事件 */
    suspend fun deleteEvent(eventId: Long)

    /** 检查日历权限 */
    suspend fun hasCalendarPermission(): Boolean

    /** 请求日历权限 */
    suspend fun requestCalendarPermission(): Boolean

    /** 获取所有日历账户 */
    suspend fun getCalendarAccounts(): List<CalendarAccount>
}

/** 日历事件配置（易于扩展） */
data class CalendarEventConfig(
    val title: String,
    val startTime: String,  // ISO 8601 格式
    val endTime: String? = null,
    val notes: String? = null,
    val location: String? = null,
    val isAllDay: Boolean = false,
    val reminderMinutes: Int? = null,
)

/** 日历账户信息 */
data class CalendarAccount(
    val id: Long,
    val name: String,
    val accountName: String,
    val accountType: String,
)
