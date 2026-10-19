package com.deadliner.domain.task

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskDeadlineShiftTest {
    @Test fun customHoursCrossMidnightAndPreservePrecision() {
        assertEquals(LocalDateTime.parse("2026-11-01T02:23:45.123456789"), LocalDateTime.parse(
            TaskDeadlineShift.postponed("2026-10-31T23:23:45.123456789", TaskDeadlineDelay(3, TaskDeadlineUnit.HOURS))!!))
    }
    @Test fun maximumWeekIsEquivalentInHoursAndDays() {
        val raw = "2026-12-28T09:00:00Z"
        assertEquals("2027-01-04T09:00:00Z", TaskDeadlineShift.postponed(raw, TaskDeadlineDelay(7, TaskDeadlineUnit.DAYS)))
        assertEquals(TaskDeadlineShift.postponed(raw, TaskDeadlineDelay(7, TaskDeadlineUnit.DAYS)),
            TaskDeadlineShift.postponed(raw, TaskDeadlineDelay(168, TaskDeadlineUnit.HOURS)))
    }
    @Test fun zeroNegativeOversizedAndOverflowingAmountsAreRejected() {
        for (unit in TaskDeadlineUnit.entries) {
            for (amount in listOf(0, -1, Int.MIN_VALUE, Int.MAX_VALUE, if (unit == TaskDeadlineUnit.HOURS) 169 else 8)) {
                val delay = TaskDeadlineDelay(amount, unit)
                assertEquals(false, delay.isValid)
                assertNull(TaskDeadlineShift.postponed("2026-10-19T09:00", delay))
            }
        }
    }
    @Test fun localDeadlinePreservesClockAndFractionsAcrossMonth() {
        assertEquals(LocalDateTime.parse("2026-11-01T18:23:45.123456789"),
            LocalDateTime.parse(TaskDeadlineShift.oneDayLater("2026-10-31T18:23:45.123456789")!!))
    }
    @Test fun leapDayAndYearBoundaries() {
        listOf("2028-02-28T09:00" to "2028-02-29T09:00",
            "2028-02-29T09:00" to "2028-03-01T09:00",
            "2026-12-31T23:59:59" to "2027-01-01T23:59:59").forEach { (before, after) ->
            assertEquals(LocalDateTime.parse(after), LocalDateTime.parse(TaskDeadlineShift.oneDayLater(before)!!))
        }
    }
    @Test fun instantAndOffsetDeadlinesAdvanceExactly24Hours() {
        assertEquals(Instant.parse("2026-10-20T01:00:00Z"),
            Instant.parse(TaskDeadlineShift.oneDayLater("2026-10-19T09:00:00+08:00")!!))
        assertEquals("2026-10-20T09:00:00Z", TaskDeadlineShift.oneDayLater("2026-10-19T09:00:00Z"))
    }
    @Test fun invalidAndAbsentDeadlinesStayAbsent() {
        listOf(null, "", "bad deadline", "2026-02-30T09:00").forEach {
            assertNull(TaskDeadlineShift.oneDayLater(it))
        }
    }
}
