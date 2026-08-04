package com.deadliner.viewmodel

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.viewmodel.habit.HabitListViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class HabitListViewModelIntegrationTest {
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
}
