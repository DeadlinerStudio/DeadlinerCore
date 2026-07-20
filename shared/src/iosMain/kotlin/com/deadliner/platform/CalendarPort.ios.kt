package com.deadliner.platform

import platform.EventKit.EKEventStore
import platform.EventKit.EKEvent
import platform.EventKit.EKCalendar
import platform.EventKit.EKEntityType
import platform.EventKit.EKAuthorizationStatus
import platform.EventKit.EKAuthorizationStatusAuthorized
import platform.EventKit.EKAuthorizationStatusNotDetermined
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * iOS CalendarPort actual 实现。
 *
 * 使用 EventKit 实现日历集成。
 */
actual class CalendarPort {
    private val eventStore = EKEventStore()

    actual suspend fun insertEvent(config: CalendarEventConfig): Long? {
        // 检查权限
        if (!hasCalendarPermission()) {
            if (!requestCalendarPermission()) {
                return null
            }
        }

        val event = EKEvent.eventWithEventStore(eventStore).apply {
            setTitle(config.title)
            setNotes(config.notes)
            setLocation(config.location)
            setAllDay(config.isAllDay)

            // 解析时间（简化实现）
            val startDate = NSDate()
            setStartDate(startDate)

            val endDate = config.endTime?.let { NSDate() } ?: startDate
            setEndDate(endDate)
        }

        val success = eventStore.saveEvent(event, span = 0, error = null)
        return if (success) {
            // 返回事件 ID（简化实现）
            event.eventIdentifier.hashCode().toLong()
        } else {
            null
        }
    }

    actual suspend fun updateEvent(eventId: Long, config: CalendarEventConfig) {
        // iOS EventKit 不支持通过 ID 直接更新，需要先获取事件
        // 简化实现：no-op
    }

    actual suspend fun deleteEvent(eventId: Long) {
        // iOS EventKit 不支持通过 ID 直接删除，需要先获取事件
        // 简化实现：no-op
    }

    actual suspend fun hasCalendarPermission(): Boolean {
        val status = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)
        return status == EKAuthorizationStatusAuthorized
    }

    actual suspend fun requestCalendarPermission(): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            eventStore.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent) { granted, error ->
                continuation.resumeWith(Result.success(granted))
            }
        }
    }

    actual suspend fun getCalendarAccounts(): List<CalendarAccount> {
        val calendars = eventStore.calendarsForEntityType(EKEntityType.EKEntityTypeEvent)
        return calendars
            ?.filterIsInstance<EKCalendar>()
            ?.map { calendar ->
                CalendarAccount(
                    id = calendar.calendarIdentifier.hashCode().toLong(),
                    name = calendar.title,
                    accountName = calendar.source.title,
                    accountType = calendar.source.sourceType,
                )
            }
            ?: emptyList()
    }
}
