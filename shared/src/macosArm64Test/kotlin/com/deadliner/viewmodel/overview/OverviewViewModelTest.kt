package com.deadliner.viewmodel.overview

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class OverviewViewModelTest {
    @Test
    fun taskOnlyMutationInvalidatesOverviewEvenWhenFocusStatisticsAreUnchanged() = runBlocking {
        val path = "${NSTemporaryDirectory()}overview-invalidation-${NSUUID().UUIDString}.db"
        val core = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "overview-invalidation-test"))
        val signals = Channel<Unit>(Channel.UNLIMITED)
        val observer = launch { core.observeOverviewChanges().collect { signals.send(Unit) } }
        try {
            withTimeout(5_000) { signals.receive() }
            val focusBefore = core.focusStatistics.overview()
            core.tasks.create(Task(uid = "new-task", title = "Task without focus",
                createdAt = "2026-10-12T00:00:00Z", updatedAt = "2026-10-12T00:00:00Z"))
            withTimeout(5_000) { signals.receive() }
            assertEquals(focusBefore, core.focusStatistics.overview())
            assertEquals(1, core.tasks.list().size)
        } finally {
            observer.cancelAndJoin()
            signals.close()
            core.close()
            remove(path)
        }
    }

    @Test
    fun selectedMonthUsesCompletionDatesLocalZoneAndCurrentArchiveInventory() {
        val path = "${NSTemporaryDirectory()}deadliner-month-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "month-test-device"))
        try {
            val vm = OverviewViewModel(database.tasks, database.habits)
            val now = Instant.parse("2026-10-12T12:00:00Z")
            fun task(uid: String, completed: String, state: TaskState = TaskState.COMPLETED) = Task(
                uid, uid, state = state, completedAt = completed, dueAt = "2026-08-10T00:00:00Z",
                createdAt = "2026-01-01T00:00:00Z", updatedAt = now.toString(),
            )
            val tasks = listOf(task("boundary", "2026-08-31T18:00:00Z"),
                task("archived", "2026-09-10T00:00:00Z", TaskState.ARCHIVED),
                task("future", "2026-10-20T00:00:00Z"),
                task("deleted", "2026-09-11T00:00:00Z").copy(isDeleted = true))
            val sept = vm.buildMonthState(kotlinx.datetime.LocalDate(2026,9,1), tasks,
                emptyList(), now, TimeZone.of("Asia/Shanghai"))
            assertEquals(2, sept.completedCount)
            assertEquals(1, sept.daily.first().completedCount)
            assertEquals(30, sept.daily.size)
            assertEquals(1, sept.archivedCount)
            val oct = vm.buildMonthState(kotlinx.datetime.LocalDate(2026,10,1), tasks,
                emptyList(), now, TimeZone.UTC)
            assertEquals(0, oct.completedCount)
            assertEquals(31, oct.daily.size)
            assertEquals(1, oct.archivedCount)
        } finally { remove(path) }
    }

    @Test
    fun monthlyStatesAreExclusiveAndMatchCompletionActivity() {
        val path = "${NSTemporaryDirectory()}overview-status-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "status-test"))
        try {
            val now = Instant.parse("2026-10-12T12:00:00Z")
            fun task(id: String, due: String, state: TaskState = TaskState.ACTIVE, completed: String? = null) = Task(
                uid = id, title = id, state = state, dueAt = due, completedAt = completed,
                createdAt = "2026-01-01T00:00:00Z", updatedAt = now.toString())
            val tasks = listOf(
                task("pending", "2026-10-20T00:00:00Z"),
                task("exact-now", now.toString()),
                task("overdue", "2026-10-01T00:00:00Z"),
                task("abandoned", "2026-10-01T00:00:00Z", TaskState.ABANDONED),
                task("abandoned-archive", "2026-10-01T00:00:00Z", TaskState.ABANDONED_ARCHIVED),
                task("completed", "2026-09-01T00:00:00Z", TaskState.COMPLETED, "2026-10-01T00:00:00Z"),
                task("archived", "2026-10-01T00:00:00Z", TaskState.ARCHIVED, "2026-10-02T00:00:00Z"),
                task("past-completion", "2026-10-01T00:00:00Z", TaskState.COMPLETED, "2026-09-01T00:00:00Z"),
                task("other-month", "2026-09-01T00:00:00Z"),
                task("future-completion", "2026-10-20T00:00:00Z", TaskState.COMPLETED, "2026-10-20T00:00:00Z"),
                task("deleted", "2026-10-01T00:00:00Z").copy(isDeleted = true),
            )
            val state = OverviewViewModel(database.tasks, database.habits).buildMonthState(
                kotlinx.datetime.LocalDate(2026,10,1), tasks, emptyList(), now, TimeZone.UTC)
            assertEquals(OverviewHistory(completed = 2, pending = 2, abandoned = 2, overdue = 1), state.statusDistribution)
            assertEquals(state.completedCount, state.statusDistribution.completed)
        } finally { database.close(); remove(path) }
    }

    @Test
    fun aggregatesOverviewMetricsFromSharedTaskAndHabitModels() {
        val path = "${NSTemporaryDirectory()}deadliner-overview-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "overview-test-device"))
        try {
            val viewModel = OverviewViewModel(database.tasks, database.habits)
            val now = Instant.parse("2026-07-22T12:00:00Z")
            val active = Task(
                uid = "active",
                title = "Due later today",
                dueAt = "2026-07-22T18:00:00Z",
                createdAt = "2026-07-22T08:00:00Z",
                updatedAt = "2026-07-22T08:00:00Z",
            )
            val completed = Task(
                uid = "completed",
                title = "Finished this morning",
                startAt = "2026-07-22T08:00:00Z",
                dueAt = "2026-07-22T10:00:00Z",
                state = TaskState.COMPLETED,
                completedAt = "2026-07-22T09:00:00Z",
                createdAt = "2026-07-22T08:00:00Z",
                updatedAt = "2026-07-22T09:00:00Z",
            )
            val overdue = Task(
                uid = "overdue",
                title = "Overdue yesterday",
                dueAt = "2026-07-21T12:00:00Z",
                createdAt = "2026-07-20T08:00:00Z",
                updatedAt = "2026-07-20T08:00:00Z",
            )
            val legacyTimestamp = Task(
                uid = "legacy-timestamp",
                title = "Legacy local date format",
                dueAt = "2026-07-22 18:00",
                state = TaskState.ABANDONED,
                createdAt = "2026-07-22T08:00:00Z",
                updatedAt = "2026-07-22T08:00:00Z",
            )
            val malformedTimestamp = Task(
                uid = "malformed-timestamp",
                title = "Malformed legacy date",
                dueAt = "not-a-timestamp",
                state = TaskState.COMPLETED,
                completedAt = "not-a-timestamp",
                createdAt = "2026-07-22T08:00:00Z",
                updatedAt = "2026-07-22T08:00:00Z",
            )
            val habit = Habit(
                uid = "habit",
                name = "Read",
                period = HabitPeriod.DAILY,
                timesPerPeriod = 2,
                createdAt = "2026-07-01T00:00:00Z",
                updatedAt = "2026-07-01T00:00:00Z",
            )

            val state = viewModel.buildState(
                tasks = listOf(active, completed, overdue, legacyTimestamp, malformedTimestamp),
                habits = listOf(habit),
                now = now,
                timeZone = TimeZone.UTC,
                habitRecords = listOf(
                    HabitRecord("record", habit.uid, "2026-07-22", count = 2, createdAt = "2026-07-22T09:00:00Z"),
                ),
            )

            assertEquals(1, state.summary.completedToday)
            assertEquals(1, state.summary.pendingDueFromNow)
            assertEquals(1, state.history.overdue)
            assertEquals(5, state.stats.totalTasks)
            assertEquals(1, state.stats.completedHabitsToday)
            assertEquals(1f, state.stats.habitProgress.single().progress)
            assertTrue(state.contribution.any { it.date == "2026-07-22" && it.completedCount == 1 })
        } finally {
            database.close()
            remove(path)
        }
    }
}
