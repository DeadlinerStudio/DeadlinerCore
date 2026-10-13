package com.deadliner.domain.focus

import com.deadliner.domain.habit.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class HabitFocusPolicyTest {
    private val date = LocalDate.parse("2026-10-11")
    private fun habit(quantity: Boolean = false) = Habit("habit", "Read",
        schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, "2026-01-01", weekdaysMask = HabitWeekdays.ALL),
        goal = HabitGoal(targetCount = if (quantity) 20 else 1),
        tracking = HabitTracking(if (quantity) HabitTrackingMode.QUANTITY else HabitTrackingMode.CHECK_OFF),
        createdAt = "2026-01-01T00:00:00Z")

    @Test fun offDayCanFocusButCannotRecord() {
        val h = habit().copy(schedule = habit().schedule.copy(weekdaysMask = HabitWeekdays.MONDAY))
        assertTrue(HabitFocusPolicy.canFocus(h))
        assertEquals(HabitActionOutcome.NOT_DUE, HabitFocusPolicy.recordDecision(h, emptyList(), date.toString(), date).outcome)
    }

    @Test fun checkOffNeverClearsAlreadyCompletedRecords() {
        val record = HabitRecord("record", "habit", date.toString(), createdAt = "2026-10-11T00:00:00Z")
        val decision = HabitFocusPolicy.recordDecision(habit(), listOf(record), date.toString(), date)
        assertEquals(HabitActionOutcome.NO_OP, decision.outcome)
        assertEquals(1, decision.targetCountForDay)
    }

    @Test fun quantityAddsActualAmountEvenPastGoal() {
        val record = HabitRecord("record", "habit", date.toString(), 20, createdAt = "2026-10-11T00:00:00Z")
        val decision = HabitFocusPolicy.recordDecision(habit(true), listOf(record), date.toString(), date, 7)
        assertEquals(HabitActionOutcome.APPLIED, decision.outcome)
        assertEquals(27, decision.targetCountForDay)
    }

    @Test fun rejectsMissingNonPositiveAndOverflowAmount() {
        for (amount in listOf(null, 0, -1)) assertEquals(HabitActionOutcome.INVALID_AMOUNT,
            HabitFocusPolicy.recordDecision(habit(true), emptyList(), date.toString(), date, amount).outcome)
        val record = HabitRecord("record", "habit", date.toString(), Int.MAX_VALUE, createdAt = "2026-10-11T00:00:00Z")
        assertEquals(HabitActionOutcome.INVALID_AMOUNT, HabitFocusPolicy.recordDecision(habit(true), listOf(record), date.toString(), date, 1).outcome)
    }

    @Test fun targetTypesCannotBeConfusedAndLegacyTaskJsonOmitsNewDefaults() {
        assertNotEquals(FocusTargetRef(FocusTargetKind.TASK, "same"), FocusTargetRef(FocusTargetKind.HABIT, "same"))
        val session = FocusSession("s", "same", "Task", FocusTimerMode.STOPWATCH,
            startedAt = "2026-10-11T00:00:00Z", endedAt = "2026-10-11T00:01:00Z", durationSeconds = 60,
            completionKind = FocusCompletionKind.FOCUS_ONLY, createdAt = "2026-10-11T00:01:00Z")
        val encoded = Json { encodeDefaults = true }.encodeToString(session)
        assertFalse("habitOccurredOn" in encoded)
        assertFalse("\"target\"" in encoded)
        assertFailsWith<IllegalArgumentException> { session.copy(target = FocusTargetRef(FocusTargetKind.HABIT, "same"), habitOccurredOn = date.toString()) }
    }

    @Test fun inactiveAndFutureRecordsAreRejected() {
        assertFalse(HabitFocusPolicy.canFocus(habit().copy(status = HabitStatus.ARCHIVED)))
        assertEquals(HabitActionOutcome.FUTURE_DATE, HabitFocusPolicy.recordDecision(habit(), emptyList(), "2026-10-12", date).outcome)
    }
}
