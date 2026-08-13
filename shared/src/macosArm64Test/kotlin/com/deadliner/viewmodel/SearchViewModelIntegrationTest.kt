package com.deadliner.viewmodel

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.task.Task
import com.deadliner.viewmodel.search.SearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class SearchViewModelIntegrationTest {
    @Test
    fun queryEnteredBeforeInitializationIsReappliedAfterRepositoriesLoad() = runBlocking {
        val path = "${NSTemporaryDirectory()}deadliner-search-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            database.tasks.create(
                Task("task", "Prepare migration", note = "KMP", createdAt = "2026-08-13T00:00:00Z", updatedAt = "2026-08-13T00:00:00Z"),
            )
            database.habits.create(
                Habit("habit", "Read KMP docs", period = HabitPeriod.DAILY, createdAt = "2026-08-13T00:00:00Z"),
            )
            val viewModel = SearchViewModel(
                taskRepository = database.tasks,
                habitRepository = database.habits,
                scope = CoroutineScope(coroutineContext),
            )

            viewModel.search("KMP")
            viewModel.initialize()
            withTimeout(5_000) {
                while (viewModel.uiState.value.isLoading) delay(10)
            }

            assertEquals(listOf("task"), viewModel.uiState.value.matchedTasks.map(Task::uid))
            assertEquals(listOf("habit"), viewModel.uiState.value.matchedHabits.map(Habit::uid))
        } finally {
            database.close()
            remove(path)
        }
    }
}
