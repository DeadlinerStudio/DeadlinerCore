package com.deadliner.viewmodel

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitGoalType
import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitGoalWindow
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitTracking
import com.deadliner.domain.habit.HabitTrackingMode
import com.deadliner.domain.habit.HabitWeekdays
import com.deadliner.viewmodel.habit.HabitListViewModel
import com.deadliner.viewmodel.habit.HabitPrimaryActionKind
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class HabitListViewModelIntegrationTest {
    @Test
    fun reloadAfterExternalArchivePreservesCalendarAndSearch() {
        val path = "${NSTemporaryDirectory()}deadliner-habit-focus-refresh-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val habit = Habit(uid = "focus-refresh", name = "Focus refresh", period = HabitPeriod.DAILY,
                createdAt = "2026-08-10T08:00:00Z")
            database.habits.create(habit)
            val viewModel = HabitListViewModel(database.habits)
            viewModel.loadNow()
            viewModel.selectDate("2026-08-13")
            viewModel.updateSearchQuery("Focus")
            assertEquals(1, viewModel.uiState.value.visibleHabits.size)
            database.habits.applyStatusAction(habit.uid, com.deadliner.domain.habit.HabitStatusAction.ARCHIVE,
                "2026-08-13T10:00:00Z")
            val refreshed = viewModel.loadNow()
            assertTrue(refreshed.visibleHabits.isEmpty())
            assertEquals("2026-08-13", refreshed.selectedDate)
            assertEquals("Focus", refreshed.searchQuery)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun pullBridgeIntentsKeepHabitAndRecordStateInOneViewModelSnapshot() {
        val path = "${NSTemporaryDirectory()}deadliner-habit-viewmodel-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val viewModel = HabitListViewModel(habitRepository = database.habits)
            val habit = Habit(
                uid = "habit-pull-intent",
                name = "Drink water",
                period = HabitPeriod.DAILY,
                createdAt = "2026-08-04T08:00:00Z",
                updatedAt = "2026-08-04T08:00:00Z",
            )

            assertEquals(listOf(habit), viewModel.saveHabitNow(habit).habits)
            val completed = viewModel.toggleHabitRecordNow(
                habit.uid,
                "2026-08-04",
                "2026-08-04T09:00:00Z",
            )
            assertEquals(1, completed.records.count { !it.isDeleted })
            assertTrue(viewModel.deleteHabitNow(habit.uid, "2026-08-04T10:00:00Z").habits.isEmpty())
            assertTrue(database.habits.find(habit.uid)?.isDeleted == true)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun selectedDateAggregatesWeeklyProgressAndSearchInSharedState() {
        val path = "${NSTemporaryDirectory()}deadliner-habit-calendar-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val habit = Habit(
                uid = "weekly",
                name = "Weekly run",
                period = HabitPeriod.WEEKLY,
                timesPerPeriod = 2,
                goalType = HabitGoalType.PER_PERIOD,
                createdAt = "2026-08-10T08:00:00Z",
            )
            database.habits.create(habit)
            database.habits.saveRecord(HabitRecord("r1", habit.uid, "2026-08-10", createdAt = "2026-08-10T09:00:00Z"))
            database.habits.saveRecord(HabitRecord("r2", habit.uid, "2026-08-12", createdAt = "2026-08-12T09:00:00Z"))

            val viewModel = HabitListViewModel(database.habits)
            viewModel.loadNow()
            viewModel.selectDate("2026-08-13")
            assertEquals(2, viewModel.uiState.value.visibleHabits.single().doneCount)
            assertTrue(viewModel.uiState.value.visibleHabits.single().isCompleted)
            assertEquals(7, viewModel.uiState.value.weekOverview.size)
            viewModel.updateSearchQuery("missing")
            assertTrue(viewModel.uiState.value.visibleHabits.isEmpty())
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun primaryActionAddsDefaultIncrementForQuantityTracking() {
        val path = "${NSTemporaryDirectory()}deadliner-habit-quantity-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
            val now = Clock.System.now().toString()
            val habit = Habit(
                uid = "words",
                name = "Learn words",
                schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, today, weekdaysMask = HabitWeekdays.ALL),
                goal = HabitGoal(targetCount = 20),
                tracking = HabitTracking(HabitTrackingMode.QUANTITY, unit = "words", defaultIncrement = 5),
                createdAt = now,
            )
            val viewModel = HabitListViewModel(database.habits)
            viewModel.saveHabitNow(habit)

            viewModel.toggleHabitRecordNow(habit.uid, today, now)
            val state = viewModel.toggleHabitRecordNow(habit.uid, today, now)
            val status = state.visibleHabits.single()

            assertEquals(10, status.doneCount)
            assertEquals(HabitPrimaryActionKind.ADD_QUANTITY, status.primaryAction.kind)
            assertEquals(5, status.primaryAction.amount)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun primaryActionResetsQuantityProgressAfterReachingTarget() {
        val path = "${NSTemporaryDirectory()}deadliner-habit-reset-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
            val habit = Habit(
                uid = "words-reset",
                name = "Learn words",
                schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, today, weekdaysMask = HabitWeekdays.ALL),
                goal = HabitGoal(targetCount = 20),
                tracking = HabitTracking(HabitTrackingMode.QUANTITY, unit = "words", defaultIncrement = 5),
                createdAt = Clock.System.now().toString(),
            )
            val viewModel = HabitListViewModel(database.habits)
            viewModel.saveHabitNow(habit)

            repeat(4) { viewModel.toggleHabitRecordNow(habit.uid, today, Clock.System.now().toString()) }
            assertEquals(20, viewModel.uiState.value.visibleHabits.single().doneCount)
            assertTrue(viewModel.uiState.value.visibleHabits.single().canApplyPrimaryAction)

            val reset = viewModel.toggleHabitRecordNow(habit.uid, today, Clock.System.now().toString())
            assertEquals(0, reset.visibleHabits.single().doneCount)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun primaryActionResetsCompletedWeeklyWindowFromAnotherDueDay() {
        val path = "${NSTemporaryDirectory()}deadliner-habit-week-reset-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val habit = Habit(
                uid = "weekly-reset",
                name = "Run twice",
                schedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, "2026-08-17", weekdaysMask = HabitWeekdays.WEEKDAYS),
                goal = HabitGoal(HabitGoalWindow.WEEK, 2),
                createdAt = "2026-08-17T00:00:00Z",
            )
            database.habits.create(habit)
            database.habits.saveRecord(HabitRecord("monday", habit.uid, "2026-08-17", createdAt = "2026-08-17T08:00:00Z"))
            database.habits.saveRecord(HabitRecord("tuesday", habit.uid, "2026-08-18", createdAt = "2026-08-18T08:00:00Z"))
            val viewModel = HabitListViewModel(database.habits)
            viewModel.loadNow()

            val reset = viewModel.toggleHabitRecordNow(habit.uid, "2026-08-19", "2026-08-19T08:00:00Z")

            assertEquals(0, reset.visibleHabits.single().doneCount)
            assertTrue(database.habits.records(habit.uid).all(HabitRecord::isDeleted))
        } finally {
            database.close()
            remove(path)
        }
    }
}
