package com.deadliner.viewmodel

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.viewmodel.task.ArchiveViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove

class ArchiveViewModelIntegrationTest {
    @Test
    fun initializeRestoreAndDeleteUseSharedArchiveState() = runBlocking {
        val path = "${NSTemporaryDirectory()}deadliner-archive-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "archive-test-device"))
        try {
            val archived = task("archived", TaskState.ARCHIVED)
            val abandoned = task("abandoned", TaskState.ABANDONED_ARCHIVED)
            val active = task("active", TaskState.ACTIVE)
            listOf(archived, abandoned, active).forEach(database.tasks::create)

            val viewModel = ArchiveViewModel(database.tasks, CoroutineScope(coroutineContext))
            viewModel.initialize()
            await { !viewModel.uiState.value.isLoading }
            assertEquals(setOf(archived.uid, abandoned.uid), viewModel.uiState.value.tasks.map { it.uid }.toSet())

            viewModel.unarchiveTask(archived.uid)
            await { viewModel.uiState.value.tasks.none { it.uid == archived.uid } }
            assertEquals(TaskState.COMPLETED, database.tasks.find(archived.uid)?.state)

            viewModel.deleteTask(abandoned.uid)
            await { viewModel.uiState.value.tasks.isEmpty() }
            assertTrue(database.tasks.find(abandoned.uid)?.isDeleted == true)
        } finally {
            database.close()
            remove(path)
        }
    }

    private fun task(uid: String, state: TaskState) = Task(
        uid = uid,
        title = uid,
        state = state,
        createdAt = "2026-08-13T08:00:00Z",
        updatedAt = "2026-08-13T08:00:00Z",
    )

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) delay(10)
        }
    }
}
