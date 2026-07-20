package com.deadliner.platform

/** Calendar access is deliberately unavailable in the macOS database-test target. */
actual class CalendarPort {
    actual suspend fun insertEvent(config: CalendarEventConfig): Long? = unsupported()
    actual suspend fun updateEvent(eventId: Long, config: CalendarEventConfig): Unit = unsupported()
    actual suspend fun deleteEvent(eventId: Long): Unit = unsupported()
    actual suspend fun hasCalendarPermission(): Boolean = unsupported()
    actual suspend fun requestCalendarPermission(): Boolean = unsupported()
    actual suspend fun getCalendarAccounts(): List<CalendarAccount> = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "CalendarPort is unavailable in the macOS development target.",
    )
}
