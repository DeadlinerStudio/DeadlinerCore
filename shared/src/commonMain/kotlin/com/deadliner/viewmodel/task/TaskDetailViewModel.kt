package com.deadliner.viewmodel.task

import com.deadliner.db.repository.TaskRepository
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.task.TaskActionOutcome
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.platform.PlatformIdentity
import com.deadliner.service.NotificationOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * 任务详情页 ViewModel。
 *
 * 核心设计：
 * 1. 持有单个任务的内存缓存
 * 2. 支持任务状态流转和子任务管理
 * 3. 使用配置对象而非多参数，易于扩展
 */
class TaskDetailViewModel(
    private val taskRepository: TaskRepository,
    private val taskUid: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val notificationOrchestrator: NotificationOrchestrator? = null,
) {
    // ===== 内存缓存 =====
    private val _uiState = MutableStateFlow(TaskDetailUiState())
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    // ===== 初始化（从 DB 加载到内存缓存）=====
    fun initialize() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val task = taskRepository.find(taskUid)
                _uiState.update {
                    it.copy(
                        task = task,
                        isLoading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message,
                    )
                }
            }
        }
    }

    // ===== 任务状态流转 =====

    fun markComplete() {
        applyAction(TaskAction.MARK_COMPLETE)
    }

    fun markArchive() {
        applyAction(TaskAction.MARK_ARCHIVE)
    }

    fun markGiveUp() {
        applyAction(TaskAction.MARK_GIVE_UP)
    }

    fun restoreActive() {
        applyAction(TaskAction.RESTORE_ACTIVE)
    }

    fun unarchive() {
        applyAction(TaskAction.UNARCHIVE)
    }

    private fun applyAction(action: TaskAction) {
        scope.launch {
            try {
                val result = taskRepository.applyAction(taskUid, action, Clock.System.now().toString())
                if (result.outcome == TaskActionOutcome.APPLIED) result.task?.let { task ->
                    notificationOrchestrator?.onTaskUpdated(task)
                    _uiState.update {
                        it.copy(task = task, error = null, lastActionOutcome = result.outcome)
                    }
                } else {
                    _uiState.update { it.copy(error = null, lastActionOutcome = result.outcome) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    // ===== 子任务管理 =====

    fun addSubtask(config: SubtaskCreateConfig) {
        scope.launch {
            try {
                val currentTask = _uiState.value.task ?: return@launch
                val subtask = TaskSubtask(
                    uid = PlatformIdentity.newId(),
                    taskUid = taskUid,
                    content = config.content,
                    isCompleted = false,
                    sortOrder = config.sortOrder,
                    createdAt = Clock.System.now().toString(),
                    updatedAt = Clock.System.now().toString(),
                )
                val updatedTask = currentTask.copy(subtasks = currentTask.subtasks + subtask)
                taskRepository.update(updatedTask)
                notificationOrchestrator?.onTaskUpdated(updatedTask)
                _uiState.update { it.copy(task = updatedTask, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun toggleSubtask(subtaskUid: String) {
        scope.launch {
            try {
                val currentTask = _uiState.value.task ?: return@launch
                val updatedSubtasks = currentTask.subtasks.map { subtask ->
                    if (subtask.uid == subtaskUid) {
                        subtask.copy(
                            isCompleted = !subtask.isCompleted,
                            updatedAt = Clock.System.now().toString(),
                        )
                    } else {
                        subtask
                    }
                }
                val updatedTask = currentTask.copy(subtasks = updatedSubtasks)
                taskRepository.update(updatedTask)
                notificationOrchestrator?.onTaskUpdated(updatedTask)
                _uiState.update { it.copy(task = updatedTask, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteSubtask(subtaskUid: String) {
        scope.launch {
            try {
                val result = taskRepository.deleteSubtask(taskUid, subtaskUid, Clock.System.now().toString())
                result.task?.let { updatedTask ->
                    if (result.outcome == TaskActionOutcome.APPLIED) {
                        notificationOrchestrator?.onTaskUpdated(updatedTask)
                    }
                    _uiState.update { it.copy(task = updatedTask, error = null) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun updateSubtaskContent(subtaskUid: String, content: String) {
        val normalized = content.trim()
        if (normalized.isEmpty()) return
        updateTask { task ->
            task.copy(subtasks = task.subtasks.map { subtask ->
                if (subtask.uid == subtaskUid) {
                    subtask.copy(content = normalized, updatedAt = Clock.System.now().toString())
                } else subtask
            })
        }
    }

    fun setStarred(isStarred: Boolean) {
        updateTask { it.copy(isStarred = isStarred, updatedAt = Clock.System.now().toString()) }
    }

    fun setCalendarEventId(calendarEventId: Long?) {
        updateTask { it.copy(calendarEventId = calendarEventId, updatedAt = Clock.System.now().toString()) }
    }

    private fun updateTask(transform: (Task) -> Task) {
        scope.launch {
            try {
                val updated = transform(_uiState.value.task ?: return@launch)
                taskRepository.update(updated)
                notificationOrchestrator?.onTaskUpdated(updated)
                _uiState.update { it.copy(task = updated, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    // ===== 删除任务 =====
    fun deleteTask() {
        scope.launch {
            try {
                val result = taskRepository.delete(taskUid, Clock.System.now().toString())
                if (result.outcome == TaskActionOutcome.APPLIED) {
                    notificationOrchestrator?.onTaskDeleted(taskUid)
                    _uiState.update {
                        it.copy(task = null, error = null, lastActionOutcome = result.outcome)
                    }
                } else {
                    _uiState.update { it.copy(error = null, lastActionOutcome = result.outcome) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }
}

// ===== UiState 定义 =====
data class TaskDetailUiState(
    val task: Task? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val lastActionOutcome: TaskActionOutcome? = null,
)

// ===== 子任务配置对象 =====
data class SubtaskCreateConfig(
    val content: String,
    val sortOrder: Int = 0,
)
