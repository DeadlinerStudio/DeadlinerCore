package com.deadliner.db

import com.deadliner.db.repository.statistics.LocalFocusStatisticsRepository
import com.deadliner.domain.focus.FocusTimerCommand
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.habit.*
import com.deadliner.domain.statistics.FocusDurationBreakdown
import com.deadliner.domain.category.Category
import com.deadliner.domain.statistics.FocusOverviewStatistics
import com.deadliner.domain.task.Task
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class FocusStatisticsRepositoryTest {
    @Test
    fun observesSessionChangesAndRecalculatesEditedDeletedAndRenamedHistory() = runBlocking {
        val path = "${NSTemporaryDirectory()}focus-statistics-${NSUUID().UUIDString}.db"
        val core = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "focus-statistics-test"))
        val updates = Channel<FocusOverviewStatistics>(Channel.UNLIMITED)
        var observer: kotlinx.coroutines.Job? = null
        try {
            val repository = LocalFocusStatisticsRepository(core.database, core.tasks, core.categories, core.focusSessions,
                nowProvider = { Instant.parse("2026-10-09T12:00:00Z") }, timeZoneProvider = { TimeZone.UTC })
            val category = Category("work", "Work", "work", "#123456", false, 0,
                "2026-10-09T08:00:00Z", "2026-10-09T08:00:00Z")
            core.categories.create(category)
            core.tasks.create(Task("task", "Original", categoryUid = "work",
                createdAt = "2026-10-09T08:00:00Z", updatedAt = "2026-10-09T08:00:00Z"))
            observer = launch { repository.observeOverview().collect { updates.send(it) } }
            assertEquals(0, updates.awaitSnapshot { true }.today.totalDurationSeconds)
            val timer = core.createFocusTimerService()
            timer.execute(FocusTimerCommand.Start("task", FocusTimerMode.STOPWATCH), Instant.parse("2026-10-09T09:00:00Z"))
            val session = timer.execute(FocusTimerCommand.Finish, Instant.parse("2026-10-09T09:10:00Z")).session!!
            assertEquals(600, updates.awaitSnapshot { it.today.totalDurationSeconds == 600L }.today.totalDurationSeconds)
            val renamed = core.tasks.find("task")!!.copy(title = "Renamed", updatedAt = "2026-10-09T10:00:00Z")
            core.tasks.update(renamed)
            assertEquals("Renamed", updates.awaitSnapshot { it.thisMonth.tasks.single().taskTitle == "Renamed" }
                .thisMonth.tasks.single().taskTitle)
            core.categories.update(category.copy(name = "Renamed category", updatedAt = "2026-10-09T10:00:30Z"))
            assertEquals("Renamed category", updates.awaitSnapshot {
                it.thisMonth.categories.single().categoryTitle == "Renamed category"
            }.thisMonth.categories.single().categoryTitle)
            core.categories.softDelete("work", "2026-10-09T10:00:45Z")
            assertEquals(null, updates.awaitSnapshot { it.thisMonth.categories.single().categoryUid == null }
                .thisMonth.categories.single().categoryUid)
            core.focusSessions.adjustDuration(session.uid, 300, "2026-10-09T10:01:00Z")
            assertEquals(300, updates.awaitSnapshot { it.today.totalDurationSeconds == 300L }.today.totalDurationSeconds)
            core.tasks.delete("task", "2026-10-09T10:02:00Z")
            val afterTaskDeletion = updates.awaitSnapshot { !it.thisMonth.tasks.single().isTaskAvailable }
            assertEquals(300, afterTaskDeletion.allTime.totalDurationSeconds)
            assertEquals("Original", afterTaskDeletion.thisMonth.tasks.single().taskTitle)
            core.focusSessions.delete(session.uid, "2026-10-09T10:03:00Z")
            assertEquals(0, updates.awaitSnapshot { it.allTime.totalDurationSeconds == 0L }.allTime.totalDurationSeconds)
        } finally {
            observer?.cancelAndJoin()
            updates.close()
            core.close()
            remove(path)
        }
    }

    @Test
    fun observesHabitMetadataAndPreservesTimeUntilSessionDeletion() = runBlocking {
        val path = "${NSTemporaryDirectory()}habit-statistics-${NSUUID().UUIDString}.db"
        val core = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "habit-statistics-test"))
        val updates = Channel<FocusOverviewStatistics>(Channel.UNLIMITED)
        var observer: kotlinx.coroutines.Job? = null
        try {
            val repository = LocalFocusStatisticsRepository(core.database, core.tasks, core.categories, core.focusSessions,
                nowProvider = { Instant.parse("2026-10-09T12:00:00Z") }, timeZoneProvider = { TimeZone.UTC }, habits = core.habits)
            core.habits.create(Habit("habit", "Original habit", schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS,
                "2026-01-01", weekdaysMask = HabitWeekdays.ALL), goal = HabitGoal(), createdAt = "2026-10-09T08:00:00Z"))
            observer = launch { repository.observeOverview().collect { updates.send(it) } }
            assertEquals(FocusDurationBreakdown(), updates.awaitSnapshot { true }.today.durationByTarget)
            val timer = core.createFocusTimerService()
            timer.execute(FocusTimerCommand.StartHabit("habit", FocusTimerMode.STOPWATCH), Instant.parse("2026-10-09T09:00:00Z"))
            val session = timer.execute(FocusTimerCommand.Finish, Instant.parse("2026-10-09T09:10:00Z")).session!!
            assertEquals(FocusDurationBreakdown(0, 600), updates.awaitSnapshot { it.today.totalDurationSeconds == 600L }.today.durationByTarget)
            val category = Category("health", "Health", "work", "#123456", false, 0,
                "2026-10-09T10:00:00Z", "2026-10-09T10:00:00Z")
            core.categories.create(category)
            core.habits.update(core.habits.find("habit")!!.copy(name = "Renamed habit", categoryUid = category.uid,
                updatedAt = "2026-10-09T10:00:00Z"))
            val renamed = updates.awaitSnapshot { it.thisMonth.targets.single().title == "Renamed habit" }
            assertEquals("Health", renamed.thisMonth.categories.single().categoryTitle)
            core.focusSessions.adjustDuration(session.uid, 300, "2026-10-09T10:01:00Z")
            assertEquals(FocusDurationBreakdown(0, 300), updates.awaitSnapshot { it.today.totalDurationSeconds == 300L }.today.durationByTarget)
            core.habits.delete("habit", "2026-10-09T10:02:00Z")
            val deleted = updates.awaitSnapshot { !it.thisMonth.targets.single().isAvailable }
            assertEquals("Original habit", deleted.thisMonth.targets.single().title)
            assertNull(deleted.thisMonth.targets.single().categoryUid)
            assertEquals(FocusDurationBreakdown(0, 300), deleted.today.durationByTarget)
            core.focusSessions.delete(session.uid, "2026-10-09T10:03:00Z")
            assertEquals(FocusDurationBreakdown(), updates.awaitSnapshot { it.today.sessionCount == 0 }.today.durationByTarget)
        } finally {
            observer?.cancelAndJoin()
            updates.close()
            core.close()
            remove(path)
        }
    }

    private suspend fun Channel<FocusOverviewStatistics>.awaitSnapshot(
        matches: (FocusOverviewStatistics) -> Boolean,
    ): FocusOverviewStatistics = withTimeout(5_000) {
        var result = receive()
        while (!matches(result)) result = receive()
        result
    }
}
