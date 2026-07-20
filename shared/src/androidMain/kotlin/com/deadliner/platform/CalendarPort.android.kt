package com.deadliner.platform

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract

/**
 * Android CalendarPort actual 实现。
 *
 * 使用 CalendarProvider 实现日历集成。
 */
actual class CalendarPort(private val context: Context) {
    actual suspend fun insertEvent(config: CalendarEventConfig): Long? {
        // 检查权限
        if (!hasCalendarPermission()) {
            if (!requestCalendarPermission()) {
                return null
            }
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, config.title)
            put(CalendarContract.Events.DESCRIPTION, config.notes)
            put(CalendarContract.Events.EVENT_LOCATION, config.location)
            put(CalendarContract.Events.ALL_DAY, if (config.isAllDay) 1 else 0)
            put(CalendarContract.Events.DTSTART, System.currentTimeMillis())
            put(CalendarContract.Events.DTEND, System.currentTimeMillis() + 3600000) // 1小时后
            put(CalendarContract.Events.CALENDAR_ID, getDefaultCalendarId())
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return uri?.lastPathSegment?.toLongOrNull()
    }

    actual suspend fun updateEvent(eventId: Long, config: CalendarEventConfig) {
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, config.title)
            put(CalendarContract.Events.DESCRIPTION, config.notes)
            put(CalendarContract.Events.EVENT_LOCATION, config.location)
        }

        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        context.contentResolver.update(uri, values, null, null)
    }

    actual suspend fun deleteEvent(eventId: Long) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        context.contentResolver.delete(uri, null, null)
    }

    actual suspend fun hasCalendarPermission(): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    actual suspend fun requestCalendarPermission(): Boolean {
        // Android 权限请求需要在 Activity 中进行
        // 这里返回 false，由原生层处理权限请求
        return false
    }

    actual suspend fun getCalendarAccounts(): List<CalendarAccount> {
        val accounts = mutableListOf<CalendarAccount>()

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
            val nameIndex = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accountNameIndex = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            val accountTypeIndex = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)

            while (cursor.moveToNext()) {
                accounts.add(
                    CalendarAccount(
                        id = cursor.getLong(idIndex),
                        name = cursor.getString(nameIndex) ?: "",
                        accountName = cursor.getString(accountNameIndex) ?: "",
                        accountType = cursor.getString(accountTypeIndex) ?: "",
                    )
                )
            }
        }

        return accounts
    }

    // ===== 辅助方法 =====

    private fun getDefaultCalendarId(): Long {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.IS_PRIMARY} = 1",
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return 1 // 默认返回 1
    }
}
