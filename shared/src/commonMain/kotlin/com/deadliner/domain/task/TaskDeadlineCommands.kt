package com.deadliner.domain.task

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Original command retained for existing platform consumers. */
interface TaskDeadlineCommands {
    fun postponeOneDay(taskUid: String, occurredAt: String): TaskDeadlineResult
}

/** Extended platform-neutral API; bounds are enforced again by the repository. */
interface TaskDeadlineAdjustmentCommands : TaskDeadlineCommands {
    fun postpone(taskUid: String, delay: TaskDeadlineDelay, occurredAt: String): TaskDeadlineResult
}

enum class TaskDeadlineUnit { HOURS, DAYS }

data class TaskDeadlineDelay(val amount: Int, val unit: TaskDeadlineUnit) {
    val maximum: Int get() = if (unit == TaskDeadlineUnit.HOURS) 168 else 7
    val isValid: Boolean get() = amount in 1..maximum
}

enum class TaskDeadlineOutcome { APPLIED, NOT_FOUND, INVALID_DEADLINE, INVALID_DELAY }
data class TaskDeadlineResult(val task: Task?, val outcome: TaskDeadlineOutcome)

/** Local values use wall-clock arithmetic. Absolute ISO values retain instant semantics. */
object TaskDeadlineShift {
    fun oneDayLater(raw: String?): String? = postponed(raw, TaskDeadlineDelay(1, TaskDeadlineUnit.DAYS))

    fun postponed(raw: String?, delay: TaskDeadlineDelay): String? {
        if (!delay.isValid || raw.isNullOrBlank()) return null
        val local = runCatching { LocalDateTime.parse(raw) }.getOrNull()
        return runCatching {
            val duration = if (delay.unit == TaskDeadlineUnit.HOURS) delay.amount.hours else delay.amount.days
            if (local != null) {
                when (delay.unit) {
                    TaskDeadlineUnit.DAYS -> LocalDateTime(local.date.plus(delay.amount, DateTimeUnit.DAY), local.time)
                    TaskDeadlineUnit.HOURS -> {
                        val totalHours = local.hour + delay.amount
                        LocalDateTime(local.date.plus(totalHours / 24, DateTimeUnit.DAY),
                            LocalTime(totalHours % 24, local.minute, local.second, local.nanosecond))
                    }
                }.toString()
            } else (Instant.parse(raw) + duration).toString()
        }.getOrNull()
    }
}
