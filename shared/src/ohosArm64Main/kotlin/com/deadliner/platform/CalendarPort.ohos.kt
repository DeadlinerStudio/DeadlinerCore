package com.deadliner.platform

/**
 * HarmonyOS CalendarPort actual 实现。
 *
 * 使用 @ohos.calendar 实现日历集成。
 */
actual class CalendarPort {
    actual suspend fun insertEvent(config: CalendarEventConfig): Long? {
        // 使用 @ohos.calendar 插入事件
        println("[HarmonyOS] insertEvent: ${config.title}")
        return null
    }

    actual suspend fun updateEvent(eventId: Long, config: CalendarEventConfig) {
        // 使用 @ohos.calendar 更新事件
        println("[HarmonyOS] updateEvent: $eventId")
    }

    actual suspend fun deleteEvent(eventId: Long) {
        // 使用 @ohos.calendar 删除事件
        println("[HarmonyOS] deleteEvent: $eventId")
    }

    actual suspend fun hasCalendarPermission(): Boolean {
        // 检查日历权限
        println("[HarmonyOS] hasCalendarPermission")
        return false
    }

    actual suspend fun requestCalendarPermission(): Boolean {
        // 请求日历权限
        println("[HarmonyOS] requestCalendarPermission")
        return false
    }

    actual suspend fun getCalendarAccounts(): List<CalendarAccount> {
        // 获取日历账户
        println("[HarmonyOS] getCalendarAccounts")
        return emptyList()
    }
}
