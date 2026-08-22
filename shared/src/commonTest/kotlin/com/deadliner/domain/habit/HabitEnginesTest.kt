package com.deadliner.domain.habit

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HabitEnginesTest {
    @Test
    fun weeklyScheduleOnlyReturnsSelectedDays() {
        val schedule = HabitSchedule(
            kind = HabitScheduleKind.WEEKLY_DAYS,
            startOn = "2026-08-17",
            weekdaysMask = HabitWeekdays.WEEKDAYS,
        )

        assertTrue(HabitScheduleEngine.state(schedule, LocalDate.parse("2026-08-18")).isDue)
        val saturday = HabitScheduleEngine.state(schedule, LocalDate.parse("2026-08-22"))
        assertFalse(saturday.isDue)
        assertEquals(HabitDueReason.WEEKDAY_NOT_SELECTED, saturday.reason)
        assertEquals("2026-08-24", saturday.nextDueOn)
    }

    @Test
    fun ebbinghausClassicKeepsFrozenOffsetsAndStopsAfterCycle() {
        val schedule = HabitSchedule(
            kind = HabitScheduleKind.ALGORITHM,
            startOn = "2026-08-01",
            algorithmKey = HabitAlgorithms.EBBINGHAUS_CLASSIC_V1,
        )

        val due = HabitScheduleEngine.dueDates(
            schedule,
            LocalDate.parse("2026-08-01"),
            LocalDate.parse("2026-10-01"),
        ).map(LocalDate::toString)

        assertEquals(
            listOf("2026-08-01", "2026-08-02", "2026-08-03", "2026-08-05", "2026-08-08", "2026-08-16", "2026-08-31", "2026-09-30"),
            due,
        )
        assertNull(HabitScheduleEngine.state(schedule, LocalDate.parse("2026-10-01")).nextDueOn)
    }

    @Test
    fun weeklyProgressUsesCalendarWeekNotSelectedDay() {
        val habit = habit(goal = HabitGoal(HabitGoalWindow.WEEK, 5))
        val records = listOf(
            record("monday", "2026-08-17", 2),
            record("tuesday", "2026-08-18", 3),
            record("previous", "2026-08-16", 10),
        )

        val progress = HabitProgressEngine.progress(habit, records, LocalDate.parse("2026-08-19"))

        assertEquals("2026-08-17", progress.windowStart)
        assertEquals("2026-08-23", progress.windowEnd)
        assertEquals(5, progress.completedCount)
        assertTrue(progress.isComplete)
    }

    @Test
    fun nonScheduledDaysDoNotEnterDailyDenominator() {
        val weekdayHabit = habit()
        val everyDayHabit = habit(uid = "daily").copy(
            schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, "2026-08-17", weekdaysMask = HabitWeekdays.ALL),
        )

        val overview = HabitProgressEngine.dayOverview(
            listOf(weekdayHabit, everyDayHabit),
            listOf(record("done", "2026-08-22", habitUid = "daily")),
            LocalDate.parse("2026-08-22"),
        )

        assertEquals(1, overview.dueHabitCount)
        assertEquals(1, overview.completedHabitCount)
        assertEquals(mapOf("daily" to true), overview.completedByHabitUid)
    }

    @Test
    fun commandPolicyRejectsOffDayAndSupportsQuantitySet() {
        val habit = habit(
            goal = HabitGoal(HabitGoalWindow.SCHEDULED_DAY, 20),
            tracking = HabitTracking(HabitTrackingMode.QUANTITY, "words", 5),
        )
        val records = listOf(record("existing", "2026-08-18", 5))

        assertEquals(
            HabitActionOutcome.NOT_DUE,
            HabitRecordCommandPolicy.evaluate(habit, records, HabitRecordCommand.Add(habit.uid, "2026-08-22", 5)).outcome,
        )
        val decision = HabitRecordCommandPolicy.evaluate(
            habit,
            records,
            HabitRecordCommand.Set(habit.uid, "2026-08-18", 20),
        )
        assertEquals(HabitActionOutcome.APPLIED, decision.outcome)
        assertEquals(20, decision.targetCountForDay)
    }

    @Test
    fun checkOffAddsOneForCycleGoalAndRejectsFutureDueDate() {
        val weekly = habit(goal = HabitGoal(HabitGoalWindow.WEEK, 3))
        val checkOff = HabitRecordCommandPolicy.evaluate(
            weekly,
            emptyList(),
            HabitRecordCommand.CheckOff(weekly.uid, "2026-08-18"),
            today = LocalDate.parse("2026-08-19"),
        )
        assertEquals(1, checkOff.targetCountForDay)

        val future = HabitRecordCommandPolicy.evaluate(
            weekly,
            emptyList(),
            HabitRecordCommand.CheckOff(weekly.uid, "2026-08-21"),
            today = LocalDate.parse("2026-08-19"),
        )
        assertEquals(HabitActionOutcome.FUTURE_DATE, future.outcome)
    }

    private fun habit(
        uid: String = "habit",
        goal: HabitGoal = HabitGoal(),
        tracking: HabitTracking = HabitTracking(),
    ) = Habit(
        uid = uid,
        name = "Read",
        schedule = HabitSchedule(
            HabitScheduleKind.WEEKLY_DAYS,
            "2026-08-17",
            weekdaysMask = HabitWeekdays.WEEKDAYS,
        ),
        goal = goal,
        tracking = tracking,
        createdAt = "2026-08-17T00:00:00Z",
    )

    private fun record(uid: String, date: String, count: Int = 1, habitUid: String = "habit") = HabitRecord(
        uid = uid,
        habitUid = habitUid,
        occurredOn = date,
        count = count,
        createdAt = "${date}T10:00:00Z",
    )
}
