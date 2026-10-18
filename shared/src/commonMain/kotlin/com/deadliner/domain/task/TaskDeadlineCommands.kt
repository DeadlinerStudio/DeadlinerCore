package com.deadliner.domain.task

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlin.time.Duration.Companion.days

/** Platform-neutral deadline commands. Implementations must load the latest persisted task. */
interface TaskDeadlineCommands {
    /** Adds one day to dueAt only. occurredAt is the mutation timestamp, not the new deadline. */
    fun postponeOneDay(taskUid: String, occurredAt: String): TaskDeadlineResult
}

enum class TaskDeadlineOutcome { APPLIED, NOT_FOUND, INVALID_DEADLINE }

data class TaskDeadlineResult(val task: Task?, val outcome: TaskDeadlineOutcome)

/** Local timestamps retain their clock time; absolute ISO timestamps advance by 24 hours. */
object TaskDeadlineShift {
    fun oneDayLater(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val local = runCatching { LocalDateTime.parse(raw) }.getOrNull()
        return if (local != null) {
            runCatching { LocalDateTime(local.date.plus(1, DateTimeUnit.DAY), local.time).toString() }.getOrNull()
        } else {
            runCatching { (Instant.parse(raw) + 1.days).toString() }.getOrNull()
        }
    }
}
