package com.deadliner.db

import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskActionOutcome
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.model.ChangeOperation
import com.deadliner.viewmodel.task.TaskDetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class TaskSubtaskDeletionTest {
    @Test fun detailDeletionPersistsAcrossReloadAndRecordsSyncTombstone() = runBlocking {
        val path = "${NSTemporaryDirectory()}subtask-delete-${NSUUID().UUIDString}.db"
        var database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "subtask-test"))
        try {
            val task = task("parent", "delete", "keep")
            database.tasks.create(task)
            val viewModel = TaskDetailViewModel(database.tasks, task.uid, CoroutineScope(coroutineContext))
            viewModel.initialize()
            await { viewModel.uiState.value.task != null }
            viewModel.deleteSubtask("delete")
            await { viewModel.uiState.value.task?.subtasks?.size == 1 }

            assertEquals(listOf("keep"), database.tasks.find(task.uid)!!.subtasks.map { it.uid })
            assertEquals(ChangeOperation.DELETE, database.changeLog.pending().last {
                it.tableName == "task_subtask" && it.recordId == "delete"
            }.operation)
            val reloaded = TaskDetailViewModel(database.tasks, task.uid, CoroutineScope(coroutineContext))
            reloaded.initialize()
            await { reloaded.uiState.value.task != null }
            assertEquals(listOf("keep"), reloaded.uiState.value.task!!.subtasks.map { it.uid })
            assertEquals(task.copy(subtasks = task.subtasks.takeLast(1),
                updatedAt = database.tasks.find(task.uid)!!.updatedAt), database.tasks.find(task.uid))
            database.close()
            database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "subtask-test"))
            assertEquals(listOf("keep"), database.tasks.find(task.uid)!!.subtasks.map { it.uid })
        } finally { database.close(); remove(path) }
    }

    @Test fun deletionIsScopedToParentAndRepeatedDeletionDoesNotWriteAgain() {
        val path = "${NSTemporaryDirectory()}subtask-scope-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "subtask-test"))
        try {
            val parent = task("parent", "own")
            val other = task("other", "foreign")
            database.tasks.create(parent)
            database.tasks.create(other)
            val initialChanges = database.changeLog.pending().size
            assertEquals(TaskActionOutcome.NO_OP,
                database.tasks.deleteSubtask(parent.uid, "foreign", "2026-10-19T00:00:00Z").outcome)
            assertEquals(TaskActionOutcome.NOT_FOUND,
                database.tasks.deleteSubtask("missing", "foreign", "2026-10-19T00:00:00Z").outcome)
            assertEquals(other, database.tasks.find(other.uid))
            assertEquals(initialChanges, database.changeLog.pending().size)

            val result = database.tasks.deleteSubtask(parent.uid, "own", "2026-10-19T00:00:00Z")
            assertEquals(TaskActionOutcome.APPLIED, result.outcome)
            assertEquals(parent.copy(subtasks = emptyList(), updatedAt = "2026-10-19T00:00:00Z"), result.task)
            val afterDelete = database.changeLog.pending().size
            assertEquals(TaskActionOutcome.NO_OP,
                database.tasks.deleteSubtask(parent.uid, "own", "2026-10-19T00:01:00Z").outcome)
            assertEquals(afterDelete, database.changeLog.pending().size)
            assertEquals(result.task, database.tasks.find(parent.uid))
        } finally { database.close(); remove(path) }
    }

    private fun task(uid: String, vararg children: String) = Task(
        uid, "Parent", dueAt = "2026-10-19T09:00", createdAt = "2026-10-17T00:00:00Z",
        updatedAt = "2026-10-17T00:00:00Z", subtasks = children.mapIndexed { index, child ->
            TaskSubtask(child, uid, child, sortOrder = index, createdAt = "2026-10-17T00:00:00Z",
                updatedAt = "2026-10-17T00:00:00Z")
        },
    )

    private suspend fun await(predicate: () -> Boolean) = withTimeout(5_000) {
        while (!predicate()) delay(10)
    }
}
