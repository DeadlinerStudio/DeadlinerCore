package com.deadliner.viewmodel.overview

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
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
