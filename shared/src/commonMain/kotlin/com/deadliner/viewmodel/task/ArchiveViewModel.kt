package com.deadliner.viewmodel.task

import com.deadliner.db.repository.TaskRepository
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskActionOutcome
import com.deadliner.domain.task.TaskState
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
 * 归档页 ViewModel。
 *
 * 核心设计：
 * 1. 只显示已归档的任务
 * 2. 支持取消归档和删除操作
 * 3. 使用内存缓存，零 IO 成本
 */
class ArchiveViewModel(
    private val taskRepository: TaskRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val notificationOrchestrator: NotificationOrchestrator? = null,
) {
    // ===== 内存缓存 =====
    private val _uiState = MutableStateFlow(ArchiveUiState())
    val uiState: StateFlow<ArchiveUiState> = _uiState.asStateFlow()

    // ===== 初始化 =====
    fun initialize() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val tasks = taskRepository.list()
                    .filter { it.state == TaskState.ARCHIVED || it.state == TaskState.ABANDONED_ARCHIVED }
                    .sortedByDescending { it.updatedAt }
                _uiState.update {
                    it.copy(
                        tasks = tasks,
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

    // ===== 取消归档 =====
    fun unarchiveTask(uid: String) {
        scope.launch {
            try {
                val result = taskRepository.applyAction(uid, com.deadliner.domain.task.TaskAction.UNARCHIVE, Clock.System.now().toString())
                if (result.outcome == TaskActionOutcome.APPLIED) result.task?.let { task ->
                    notificationOrchestrator?.onTaskUpdated(task)
                    // 从列表中移除已取消归档的任务
                    _uiState.update { state ->
                        state.copy(
                            tasks = state.tasks.filter { it.uid != uid },
                            error = null,
                            lastActionOutcome = result.outcome,
                        )
                    }
                } else {
                    _uiState.update { it.copy(error = null, lastActionOutcome = result.outcome) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    // ===== 删除任务 =====
    fun deleteTask(uid: String) {
        scope.launch {
            try {
                val result = taskRepository.delete(uid, Clock.System.now().toString())
                if (result.outcome == TaskActionOutcome.APPLIED) {
                    notificationOrchestrator?.onTaskDeleted(uid)
                    _uiState.update { state ->
                        state.copy(
                            tasks = state.tasks.filter { it.uid != uid },
                            error = null,
                            lastActionOutcome = result.outcome,
                        )
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
data class ArchiveUiState(
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val lastActionOutcome: TaskActionOutcome? = null,
)
