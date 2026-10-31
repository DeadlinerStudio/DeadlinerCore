package com.deadliner.domain.focus.management

import com.deadliner.domain.focus.*
import com.deadliner.domain.habit.*
import com.deadliner.domain.task.*
import kotlinx.datetime.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class FocusManagementEngineTest {
    private val day = LocalDate.parse("2026-10-14")
    private val stamp = "2026-10-14T08:00:00Z"
    private fun session(uid: String, kind: FocusTargetKind = FocusTargetKind.TASK, start: String = stamp, seconds: Long = 300) = FocusSession(
        uid, if (kind == FocusTargetKind.TASK) "same" else "", "Original", FocusTimerMode.STOPWATCH,
        startedAt = start, endedAt = (Instant.parse(start) + seconds.seconds).toString(), durationSeconds = seconds,
        completionKind = FocusCompletionKind.FOCUS_ONLY, createdAt = start,
        target = FocusTargetRef(kind, "same"), habitOccurredOn = if (kind == FocusTargetKind.HABIT) "2026-10-14" else null)
    private fun project(rows: List<FocusSession>, tasks: List<Task> = emptyList(), habits: List<Habit> = emptyList(), zone: TimeZone = TimeZone.UTC) =
        FocusManagementEngine(rows, tasks, habits, emptyList(), zone).snapshot(day)

    @Test fun pagesKeepFullDayTotalsAndStableTiesWithoutMixingKinds() {
        val rows = (0..119).map { session(it.toString().padStart(3, '0')) } + session("habit", FocusTargetKind.HABIT)
        val data = project(rows)
        val first = data.historyPage(50)
        assertEquals(121, first.totalCount)
        assertEquals(36300L, first.days.single().durationSeconds)
        assertEquals(50, first.days.single().records.size)
        assertTrue(first.hasMore)
        assertEquals(data.records.take(100).map { it.session.uid }, data.historyPage(100).days.single().records.map { it.session.uid })
        assertEquals(1, data.historyPage(50, FocusTargetRef(FocusTargetKind.HABIT, "same")).totalCount)
        assertEquals(120, data.historyPage(150, FocusTargetRef(FocusTargetKind.TASK, "same")).totalCount)
        assertFalse(data.historyPage(150).hasMore)
    }
    @Test fun usesLocalStartDayAndSevenBoundedPointsExcludingDeletedRows() {
        val data = project(listOf(session("previous", start = "2026-10-13T15:59:00Z"),
            session("today", start = "2026-10-13T16:00:00Z"), session("deleted").copy(isDeleted = true)), zone = TimeZone.of("Asia/Shanghai"))
        assertEquals(300L, data.today.totalDurationSeconds)
        assertEquals(600L, data.allTime.summary.totalDurationSeconds)
        assertEquals(7, data.recentSevenDays.daily.size)
        assertEquals(300L, data.recentSevenDays.daily.last().durationSeconds)
    }
    @Test fun currentNamesAndStartEligibilityAreIndependentOfSavedHistory() {
        val task = Task("same", "Renamed", createdAt = stamp, updatedAt = stamp)
        val completed = task.copy(uid = "done", state = TaskState.COMPLETED)
        val habit = Habit("same", "Habit", schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, "2026-01-01", weekdaysMask = 1), goal = HabitGoal(), createdAt = stamp)
        val data = project(listOf(session("one"), session("habit", FocusTargetKind.HABIT)), listOf(task, completed), listOf(habit))
        assertEquals(setOf("Renamed", "Habit"), data.records.map { it.title }.toSet())
        assertEquals(2, data.startTargets.size) // Off-schedule active Habit is still eligible.
        assertTrue(data.records.all { it.targetStatus == FocusManagedTargetStatus.ACTIVE })
        val deleted = project(listOf(session("one")), listOf(task.copy(isDeleted = true)))
        assertEquals("Original", deleted.records.single().title)
        assertEquals(FocusManagedTargetStatus.DELETED, deleted.records.single().targetStatus)
    }
    @Test fun correctionsCannotInflateAndLongHistoryNeverCreatesUnboundedDailySeries() {
        assertEquals(120L, maximumFocusAdjustment(session("one", seconds = 120)))
        val data = project(listOf(session("old", start = "2020-01-01T00:00:00Z")))
        assertTrue(data.allTime.daily.isEmpty())
        assertEquals(7, data.recentSevenDays.daily.size)
        assertEquals(0L, data.recentSevenDays.summary.totalDurationSeconds)
    }
    @Test fun tenThousandRecordsKeepAllHistoryAccessibleWithBoundedChart() {
        val data = project((0 until 10000).map { session(it.toString()) })
        assertEquals(10000, data.historyPage(50).totalCount)
        assertEquals(3000000L, data.historyPage(50).days.single().durationSeconds)
        assertEquals(10000, data.historyPage(10000).days.single().records.size)
        assertEquals(7, data.recentSevenDays.daily.size)
    }

}
