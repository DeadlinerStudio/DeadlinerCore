package com.deadliner.service

import com.deadliner.domain.task.*
import com.deadliner.domain.habit.*
import com.deadliner.platform.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.*

class NotificationMetadataPlannerTest {
    private val due = "2026-10-07T12:00:00Z"
    private val task = Task("task", "Report", dueAt = due, createdAt = due, updatedAt = due)
    private fun plan(at: String, tasks: List<Task> = listOf(task), habits: List<Habit> = emptyList(),
        policy: NotificationPolicy = NotificationPolicy(), state: NotificationInteractionState = NotificationInteractionState(),
        records: List<HabitRecord> = emptyList()) = NotificationMetadataPlanner.plan(tasks, habits, { records }, policy, state,
            Instant.parse(at), TimeZone.UTC)
    private fun habit(time: String = "12:00") = Habit("habit", "Read", schedule = HabitSchedule(
        HabitScheduleKind.WEEKLY_DAYS, "2026-10-01", weekdaysMask = HabitWeekdays.ALL),
        goal = HabitGoal(), reminder = HabitReminder(time), createdAt = due)

    @Test fun taskWindowIsInclusiveAtStartAndExclusiveAtEnd() {
        assertTrue(plan("2026-10-07T10:59:59Z").realtime.isEmpty())
        assertEquals(NotificationPhase.UPCOMING, plan("2026-10-07T11:00:00Z").realtime.single().phase)
        assertEquals(NotificationPhase.OVERDUE, plan(due).realtime.single().phase)
        assertEquals(NotificationPhase.OVERDUE, plan("2026-10-07T12:59:59Z").realtime.single().phase)
        assertTrue(plan("2026-10-07T13:00:00Z").realtime.isEmpty())
    }
    @Test fun ordinaryAlarmsAreExactlyOneDayBeforeAndAtDeadline() {
        val metadata = plan("2026-10-06T10:00:00Z")
        assertEquals(listOf(NotificationKind.TASK_NEARBY, NotificationKind.TASK_DUE), metadata.alarms.map { it.kind })
        assertEquals(listOf("2026-10-06T12:00:00Z", due), metadata.alarms.map { it.triggerAt })
        assertEquals("2026-10-07T11:00:00Z", metadata.nextRefreshAt)
    }
    @Test fun threeTaskSwitchesAreIndependentAndInvalidTasksDoNotOwnNotifications() {
        val policy = NotificationPolicy(taskRealtime = false, taskNearby = false, taskDue = true)
        assertEquals(listOf(NotificationKind.TASK_DUE), plan("2026-10-06T10:00:00Z", policy = policy).alarms.map { it.kind })
        assertTrue(plan("2026-10-07T11:30:00Z", policy = policy).realtime.isEmpty())
        assertTrue(plan("2026-10-07T11:30:00Z", tasks = listOf(task.copy(isDeleted = true), task.copy(dueAt = "invalid"))).alarms.isEmpty())
    }
    @Test fun completionAndAbandonmentShowReceiptForThreeMinutesWithRestoreAction() {
        listOf(TaskState.COMPLETED to NotificationPhase.COMPLETED, TaskState.ABANDONED to NotificationPhase.ABANDONED).forEach { (state, phase) ->
            val terminal = task.copy(state = state, updatedAt = "terminal-version")
            val receipt = NotificationInteractionState(listOf(TaskNotificationResult(task.uid, terminal.updatedAt, phase, "2026-10-07T11:30:00Z")))
            val metadata = plan("2026-10-07T11:32:59Z", listOf(terminal), state = receipt)
            assertEquals(phase, metadata.realtime.single().phase)
            assertEquals(listOf(NotificationAction.RESTORE_ACTIVE), metadata.realtime.single().actions)
            assertEquals("2026-10-07T11:33:00Z", metadata.nextRefreshAt)
            assertTrue(plan("2026-10-07T11:33:00Z", listOf(terminal), state = receipt).realtime.isEmpty())
            assertTrue(plan("2026-10-07T11:31:00Z", listOf(terminal.copy(isDeleted = true)), state = receipt).realtime.isEmpty())
        }
    }
    @Test fun oldOrOutsideWindowResultCannotResurrectRealtimeCard() {
        val terminal = task.copy(state = TaskState.COMPLETED, updatedAt = "new")
        val wrongVersion = NotificationInteractionState(listOf(TaskNotificationResult(task.uid, "old", NotificationPhase.COMPLETED, "2026-10-07T11:30:00Z")))
        assertTrue(plan("2026-10-07T11:31:00Z", listOf(terminal), state = wrongVersion).realtime.isEmpty())
        val outside = wrongVersion.copy(taskResults = listOf(TaskNotificationResult(task.uid, "new", NotificationPhase.COMPLETED, "2026-10-07T14:00:00Z")))
        assertTrue(plan("2026-10-07T14:01:00Z", listOf(terminal), state = outside).realtime.isEmpty())
    }
    @Test fun habitModesAreExclusiveAndRealtimeEndsAfterOneHour() {
        val realtime = NotificationPolicy(habitMode = HabitNotificationMode.REALTIME)
        val atStart = plan(due, emptyList(), listOf(habit()), realtime)
        assertEquals(NotificationPhase.HABIT_DUE, atStart.realtime.single().phase)
        assertEquals(listOf(NotificationAction.IGNORE, NotificationAction.CHECK_IN), atStart.realtime.single().actions)
        assertTrue(atStart.alarms.all { it.kind == NotificationKind.HABIT_START })
        assertTrue(plan("2026-10-07T13:00:00Z", emptyList(), listOf(habit()), realtime).realtime.isEmpty())
        val ordinary = plan("2026-10-07T11:00:00Z", emptyList(), listOf(habit()))
        assertEquals(NotificationKind.HABIT_DUE, ordinary.alarms.single().kind)
        assertEquals(due, ordinary.alarms.single().triggerAt)
        assertTrue(ordinary.realtime.isEmpty())
    }
    @Test fun ignoredOccurrenceDoesNotSuppressTomorrowAndMidnightUsesOccurrenceDate() {
        val policy = NotificationPolicy(habitMode = HabitNotificationMode.REALTIME)
        val metadata = plan("2026-10-08T00:15:00Z", emptyList(), listOf(habit("23:30")), policy)
        val entry = metadata.realtime.single()
        assertEquals("2026-10-07", entry.occurrenceOn)
        val ignored = NotificationInteractionState(dismissedHabitOccurrences = setOf(entry.key))
        assertTrue(plan("2026-10-08T00:15:00Z", emptyList(), listOf(habit("23:30")), policy, ignored).realtime.isEmpty())
        assertEquals(1, plan("2026-10-08T23:45:00Z", emptyList(), listOf(habit("23:30")), policy, ignored).realtime.size)
    }
    @Test fun completedHabitAndArchivedHabitDoNotReappear() {
        val record = HabitRecord("record", "habit", "2026-10-07", createdAt = due)
        val policy = NotificationPolicy(habitMode = HabitNotificationMode.REALTIME)
        assertTrue(plan("2026-10-07T12:15:00Z", emptyList(), listOf(habit()), policy, records = listOf(record)).realtime.isEmpty())
        assertTrue(plan(due, emptyList(), listOf(habit().copy(status = HabitStatus.ARCHIVED)), policy).alarms.isEmpty())
    }
    @Test fun checkInProducesSharedProgressAndExpiresWithoutReappearingWhenComplete() {
        val policy = NotificationPolicy(habitMode = HabitNotificationMode.REALTIME)
        val record = HabitRecord("result", "habit", "2026-10-07", createdAt = "2026-10-07T12:30:00Z")
        val entry = plan("2026-10-07T12:31:00Z", emptyList(), listOf(habit()), policy, records = listOf(record)).realtime.single()
        assertEquals(NotificationPhase.HABIT_CHECKED_IN, entry.phase)
        assertEquals(1, entry.completedCount); assertEquals(1, entry.targetCount)
        assertEquals(HabitGoalWindow.SCHEDULED_DAY, entry.habitWindow)
        assertEquals(listOf(NotificationAction.IGNORE), entry.actions)
        assertEquals("2026-10-07T12:33:00Z", entry.endsAt)
        assertTrue(plan("2026-10-07T12:33:00Z", emptyList(), listOf(habit()), policy, records = listOf(record)).realtime.isEmpty())
        assertEquals(NotificationPhase.HABIT_DUE, plan("2026-10-07T12:31:00Z", emptyList(), listOf(habit()), policy,
            records = listOf(record.copy(isDeleted = true))).realtime.single().phase)
    }
    @Test fun weeklyPartialCheckInIncludesWindowCountAndRestoresReminderAtExpiry() {
        val policy = NotificationPolicy(habitMode = HabitNotificationMode.REALTIME)
        val weekly = habit().copy(goal = HabitGoal(HabitGoalWindow.WEEK, 3))
        val records = listOf(HabitRecord("previous", "habit", "2026-10-06", createdAt = "2026-10-06T12:15:00Z"),
            HabitRecord("current", "habit", "2026-10-07", createdAt = "2026-10-07T12:30:00Z"),
            HabitRecord("outside", "habit", "2026-09-30", createdAt = "2026-09-30T12:15:00Z"))
        val before = plan("2026-10-07T12:29:00Z", emptyList(), listOf(weekly), policy, records = records.take(1)).realtime.single()
        val after = plan("2026-10-07T12:31:00Z", emptyList(), listOf(weekly), policy, records = records).realtime.single()
        assertEquals(2, after.completedCount); assertEquals(3, after.targetCount)
        assertNotEquals(before.version, after.version)
        val restored = plan("2026-10-07T12:33:00Z", emptyList(), listOf(weekly), policy, records = records).realtime.single()
        assertEquals(NotificationPhase.HABIT_DUE, restored.phase)
        assertTrue(NotificationAction.CHECK_IN in restored.actions)
        assertEquals("2026-10-07T13:00:00Z", restored.endsAt)
    }
    @Test fun lastMinuteAndMidnightCheckInKeepsReceiptForFullThreeMinutes() {
        val policy = NotificationPolicy(habitMode = HabitNotificationMode.REALTIME)
        val record = HabitRecord("late", "habit", "2026-10-07", createdAt = "2026-10-07T23:59:00Z")
        val meta = plan("2026-10-08T00:01:00Z", emptyList(), listOf(habit("23:00")), policy, records = listOf(record))
        assertEquals(NotificationPhase.HABIT_CHECKED_IN, meta.realtime.single().phase)
        assertEquals("2026-10-08T00:02:00Z", meta.nextRefreshAt)
        assertTrue(plan("2026-10-08T00:02:00Z", emptyList(), listOf(habit("23:00")), policy, records = listOf(record)).realtime.isEmpty())
    }

}
