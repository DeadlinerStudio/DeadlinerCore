package com.deadliner.viewmodel

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.platform.DailySummaryConfig
import com.deadliner.platform.HabitNotificationConfig
import com.deadliner.platform.HabitNotificationInfo
import com.deadliner.platform.NotificationGateway
import com.deadliner.platform.TaskNotificationConfig
import com.deadliner.platform.TaskNotificationInfo
import com.deadliner.platform.UpcomingLiveUpdateConfig
import com.deadliner.service.NotificationOrchestrator
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.task.TaskState
import com.deadliner.viewmodel.task.TaskCreateConfig
import com.deadliner.viewmodel.task.TaskListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class TaskListViewModelIntegrationTest {
    @Test
    fun filteredTasksKeepActionableAndStarredTasksAheadOfDueDate() {
        val path = "${NSTemporaryDirectory()}deadliner-viewmodel-sort-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val viewModel = TaskListViewModel(taskRepository = database.tasks)
            listOf(
                Task("completed", "Completed", dueAt = "2026-08-01T08:00:00Z", state = TaskState.COMPLETED, createdAt = "2026-08-01T00:00:00Z", updatedAt = "2026-08-01T00:00:00Z"),
                Task("normal", "Normal", dueAt = "2026-08-02T08:00:00Z", createdAt = "2026-08-01T00:00:01Z", updatedAt = "2026-08-01T00:00:01Z"),
                Task("starred", "Starred", dueAt = "2026-08-03T08:00:00Z", isStarred = true, createdAt = "2026-08-01T00:00:02Z", updatedAt = "2026-08-01T00:00:02Z"),
            ).forEach(database.tasks::create)

            viewModel.loadNow()

            assertEquals(listOf("starred", "normal", "completed"), viewModel.getFilteredTasks().map(Task::uid))
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun pullBridgeIntentsUseViewModelStateForFullFidelityTasks() {
        val path = "${NSTemporaryDirectory()}deadliner-viewmodel-pull-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val viewModel = TaskListViewModel(taskRepository = database.tasks)
            val task = Task(
                uid = "task-pull-intent",
                title = "ViewModel authority",
                dueAt = "2026-08-04T10:00:00Z",
                createdAt = "2026-08-04T08:00:00Z",
                updatedAt = "2026-08-04T08:00:00Z",
            )

            assertEquals(listOf(task), viewModel.saveTaskNow(task).tasks)
            assertEquals(
                "COMPLETED",
                viewModel.applyTaskActionNow(task.uid, TaskAction.MARK_COMPLETE, "2026-08-04T09:00:00Z")
                    .tasks.single().state.name,
            )
            assertTrue(viewModel.deleteTaskNow(task.uid, "2026-08-04T09:30:00Z").tasks.isEmpty())
            assertTrue(database.tasks.find(task.uid)?.isDeleted == true)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun createCompleteAndDeleteKeepSqliteStateAndNotificationLifecycleAligned() = runBlocking {
        val path = "${NSTemporaryDirectory()}deadliner-viewmodel-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val gateway = RecordingNotificationGateway()
            val viewModel = TaskListViewModel(
                taskRepository = database.tasks,
                notificationOrchestrator = NotificationOrchestrator(gateway),
                scope = CoroutineScope(coroutineContext),
            )

            viewModel.createTask(TaskCreateConfig(title = "Ship Phase 3", dueAt = "2026-08-01T09:00:00Z"))
            await { viewModel.uiState.value.tasks.size == 1 }
            val task = viewModel.uiState.value.tasks.single()
            assertEquals(task.uid, gateway.taskSchedules.single().taskUid)
            assertEquals(task, database.tasks.find(task.uid))

            viewModel.toggleTaskComplete(task.uid)
            await { viewModel.uiState.value.tasks.single().state.name == "COMPLETED" }
            assertTrue(gateway.cancelledTaskUids.contains(task.uid))

            viewModel.deleteTask(task.uid)
            await { viewModel.uiState.value.tasks.isEmpty() }
            assertTrue(database.tasks.find(task.uid)?.isDeleted == true)
            assertTrue(gateway.cancelledTaskUids.count { it == task.uid } >= 2)
        } finally {
            database.close()
            remove(path)
        }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) delay(10)
        }
    }
}

private class RecordingNotificationGateway : NotificationGateway {
    val taskSchedules = mutableListOf<TaskNotificationConfig>()
    val cancelledTaskUids = mutableListOf<String>()

    override fun setTaskNotification(config: TaskNotificationConfig) { taskSchedules += config }
    override fun cancelTaskNotification(taskUid: String) { cancelledTaskUids += taskUid }
    override fun cancelAllTaskNotifications() = Unit
    override fun setHabitNotification(config: HabitNotificationConfig) = Unit
    override fun cancelHabitNotification(habitUid: String) = Unit
    override fun cancelAllHabitNotifications() = Unit
    override fun setDailySummaryNotification(config: DailySummaryConfig) = Unit
    override fun cancelDailySummaryNotification() = Unit
    override fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig) = Unit
    override fun cancelUpcomingLiveUpdate(taskUid: String) = Unit
    override fun cancelAll() = Unit
    override fun refreshAll(tasks: List<TaskNotificationInfo>, habits: List<HabitNotificationInfo>) = Unit
}
