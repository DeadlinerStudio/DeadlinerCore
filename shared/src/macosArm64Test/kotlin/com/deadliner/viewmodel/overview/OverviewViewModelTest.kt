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
    fun rangeTitlesFollowCompletionDatesIncludingTasksWithoutDueDates() {
        val path = "${NSTemporaryDirectory()}overview-range-titles-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "range-titles-test"))
        try {
            val now = Instant.parse("2026-11-03T12:00:00Z")
            fun done(id: String, due: String?, completed: String) = Task(
                uid = id, title = id, state = TaskState.COMPLETED, dueAt = due, completedAt = completed,
                createdAt = "2026-01-01T00:00:00Z", updatedAt = now.toString())
            val tasks = listOf(
                done("late-completion", "2026-09-20T00:00:00Z", "2026-10-01T00:00:00Z"),
                done("no-due-date", null, "2026-10-16T00:00:00Z"),
                done("completed-before-range", "2026-10-20T00:00:00Z", "2026-09-25T00:00:00Z"),
                done("exclusive-end", "2026-10-20T00:00:00Z", "2026-11-01T00:00:00Z"),
            )
            val range = OverviewViewModel(database.tasks, database.habits).buildRangeState(
                kotlinx.datetime.LocalDate(2026, 10, 1), kotlinx.datetime.LocalDate(2026, 11, 1), tasks, now, TimeZone.UTC)
            assertEquals(2, range.completedCount)
            assertEquals(listOf("late-completion", "no-due-date"), range.completedTaskTitles)
            assertEquals(range.completedCount, range.completionBuckets.sumOf { it.completedCount })
        } finally { database.close(); remove(path) }
    }

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
    fun wholeMonthRangeMatchesMonthlyProjection() {
        val path = "${NSTemporaryDirectory()}overview-range-month-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "range-month-test"))
        try {
            val now = Instant.parse("2026-10-12T12:00:00Z")
            fun task(id: String, due: String, state: TaskState = TaskState.ACTIVE, completed: String? = null) = Task(
                uid = id, title = id, state = state, dueAt = due, completedAt = completed,
                createdAt = "2026-01-01T00:00:00Z", updatedAt = now.toString())
            val tasks = listOf(
                task("pending", "2026-10-20T00:00:00Z"),
                task("overdue", "2026-10-01T00:00:00Z"),
                task("abandoned", "2026-10-01T00:00:00Z", TaskState.ABANDONED),
                task("completed", "2026-09-01T00:00:00Z", TaskState.COMPLETED, "2026-10-01T00:00:00Z"),
                task("archived", "2026-10-01T00:00:00Z", TaskState.ARCHIVED, "2026-10-02T00:00:00Z"),
                task("future-completion", "2026-10-20T00:00:00Z", TaskState.COMPLETED, "2026-10-20T00:00:00Z"),
            )
            val vm = OverviewViewModel(database.tasks, database.habits)
            val month = vm.buildMonthState(kotlinx.datetime.LocalDate(2026, 10, 1), tasks, emptyList(), now, TimeZone.UTC)
            val range = vm.buildRangeState(kotlinx.datetime.LocalDate(2026, 10, 1), kotlinx.datetime.LocalDate(2026, 11, 1),
                tasks, now, TimeZone.UTC)
            assertEquals(month.statusDistribution, range.statusDistribution)
            assertEquals(month.completedCount, range.completedCount)
            assertEquals(month.daily, range.daily)
            assertEquals(range.completedCount, range.completionBuckets.sumOf { it.completedCount })
        } finally { database.close(); remove(path) }
    }

    @Test
    fun customRangeSpansMonthsIntoTheFutureAndComparesWithPrecedingPeriod() {
        val path = "${NSTemporaryDirectory()}overview-range-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "range-test"))
        try {
            val now = Instant.parse("2026-11-02T12:00:00Z")
            fun task(id: String, due: String, state: TaskState = TaskState.ACTIVE, completed: String? = null) = Task(
                uid = id, title = id, state = state, dueAt = due, completedAt = completed,
                createdAt = "2026-01-01T00:00:00Z", updatedAt = now.toString())
            val tasks = listOf(
                task("before-range", "2026-10-11T00:00:00Z", TaskState.COMPLETED, "2026-10-11T08:00:00Z"),
                task("in-range-done", "2026-10-15T00:00:00Z", TaskState.COMPLETED, "2026-10-14T20:00:00Z"),
                task("in-range-overdue", "2026-10-20T00:00:00Z"),
                task("future-due", "2026-12-31T09:00:00Z"),
                task("previous-period", "2026-08-01T00:00:00Z", TaskState.COMPLETED, "2026-08-01T00:00:00Z"),
            )
            // 10 月 12 日到 12 月 31 日（含），共 81 天。
            val state = OverviewViewModel(database.tasks, database.habits).buildRangeState(
                kotlinx.datetime.LocalDate(2026, 10, 12), kotlinx.datetime.LocalDate(2027, 1, 1), tasks, now, TimeZone.UTC)
            assertEquals(81, state.daily.size)
            assertEquals(1, state.completedCount)
            assertEquals(OverviewHistory(completed = 1, pending = 1, abandoned = 0, overdue = 1), state.statusDistribution)
            assertEquals(1, state.completionBuckets.single { it.period == OverviewCompletionPeriod.EVENING }.completedCount)
            assertEquals(3, state.metrics.totalTasks)
            // 前一个等长时段是 7 月 23 日到 10 月 11 日：含 8 月 1 日和 10 月 11 日到期的两项。
            assertEquals(2, state.metrics.previousTotalTasks)
            assertEquals(listOf("in-range-done"), state.completedTaskTitles)
            assertEquals("2026-11-02", state.asOfDate)
        } finally { database.close(); remove(path) }
    }

    @Test
    fun emptyOrReversedRangesAreRejected() {
        val path = "${NSTemporaryDirectory()}overview-range-invalid-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "range-invalid-test"))
        try {
            val vm = OverviewViewModel(database.tasks, database.habits)
            val day = kotlinx.datetime.LocalDate(2026, 10, 12)
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                vm.buildRangeState(day, day, emptyList(), Instant.parse("2026-11-02T12:00:00Z"), TimeZone.UTC)
            }
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
