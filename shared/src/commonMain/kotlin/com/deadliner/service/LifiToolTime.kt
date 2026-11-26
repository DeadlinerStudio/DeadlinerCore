package com.deadliner.service

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Offset timestamps represent an instant; persist it in the application's local calendar. */
object LifiToolTime {
    fun parse(raw: String?, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty)?.replace(' ', 'T') ?: return null
        return runCatching { LocalDateTime.parse(value) }.getOrNull()
            ?: runCatching { Instant.parse(value).toLocalDateTime(zone) }.getOrNull()
    }
}
