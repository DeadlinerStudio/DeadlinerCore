package com.deadliner.viewmodel.task

import com.deadliner.db.repository.TaskRepository
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.task.TaskState
import com.deadliner.platform.PlatformIdentity
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
 * 任务列表页 ViewModel。
 *
 * 核心设计：
 * 1. 持有内存缓存 StateFlow，UI 直接订阅，零 IO 成本
 * 2. 写操作后同步更新内存缓存，无需重新查询 DB
 * 3. 使用配置对象而非多参数，易于扩展
 */
class TaskListViewModel(
    private val taskRepository: TaskRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
) {
    // ===== 内存缓存（IO 优化核心）=====
    private val _uiState = MutableStateFlow(TaskListUiState())
    val uiState: StateFlow<TaskListUiState> = _uiState.asStateFlow()

    // ===== 初始化（从 DB 加载到内存缓存）=====
    fun initialize() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val tasks = taskRepository.list()
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

    // ===== 写操作（同步更新内存缓存）=====

    fun createTask(config: TaskCreateConfig) {
        scope.launch {
            try {
                val task = Task(
                    uid = PlatformIdentity.newId(),
                    title = config.title,
                    note = config.note,
                    startAt = config.startAt,
                    dueAt = config.dueAt,
                    state = TaskState.ACTIVE,
                    categoryUid = config.categoryUid,
                    isStarred = config.isStarred,
                    calendarEventId = config.calendarEventId,
                    createdAt = Clock.System.now().toString(),
                    updatedAt = Clock.System.now().toString(),
                )
                taskRepository.create(task)
                // 直接更新内存缓存，无需重新查询 DB
                _uiState.update { state ->
                    state.copy(
                        tasks = state.tasks + task,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message)
                }
            }
        }
    }

    fun toggleTaskComplete(uid: String) {
        scope.launch {
            try {
                val result = taskRepository.applyAction(uid, TaskAction.MARK_COMPLETE, Clock.System.now().toString())
                result.task?.let { task ->
                    // 直接更新内存缓存中的特定任务
                    _uiState.update { state ->
                        state.copy(
                            tasks = state.tasks.map { if (it.uid == uid) task else it },
                            error = null,
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message)
                }
            }
        }
    }

    fun deleteTask(uid: String) {
        scope.launch {
            try {
                taskRepository.delete(uid, Clock.System.now().toString())
                // 从内存缓存中移除
                _uiState.update { state ->
                    state.copy(
                        tasks = state.tasks.filter { it.uid != uid },
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message)
                }
            }
        }
    }

    fun updateFilter(filter: TaskFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun updateSortBy(sortBy: TaskSortBy) {
        _uiState.update { it.copy(sortBy = sortBy) }
    }

    // ===== 过滤后的任务列表 =====
    fun getFilteredTasks(): List<Task> {
        val state = _uiState.value
        val filtered = when (state.filter) {
            TaskFilter.ALL -> state.tasks
            TaskFilter.ACTIVE -> state.tasks.filter { it.state == TaskState.ACTIVE }
            TaskFilter.COMPLETED -> state.tasks.filter { it.state == TaskState.COMPLETED }
            TaskFilter.ARCHIVED -> state.tasks.filter { it.state == TaskState.ARCHIVED }
            is TaskFilter.BY_CATEGORY -> state.tasks.filter { it.categoryUid == state.filter.categoryUid }
        }
        return when (state.sortBy) {
            TaskSortBy.DUE_DATE -> filtered.sortedBy { it.dueAt }
            TaskSortBy.CREATED_AT -> filtered.sortedByDescending { it.createdAt }
            TaskSortBy.PRIORITY -> filtered.sortedByDescending { it.isStarred }
            TaskSortBy.TITLE -> filtered.sortedBy { it.title }
        }
    }
}

// ===== UiState 定义（使用数据类，易于扩展）=====
data class TaskListUiState(
    val tasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val filter: TaskFilter = TaskFilter.ALL,
    val sortBy: TaskSortBy = TaskSortBy.DUE_DATE,
)

// ===== 配置对象（易于扩展新参数）=====
data class TaskCreateConfig(
    val title: String,
    val note: String = "",
    val startAt: String? = null,
    val dueAt: String? = null,
    val categoryUid: String? = null,
    val isStarred: Boolean = false,
    val calendarEventId: Long? = null,
    // 未来可轻松添加新字段
    // val tags: List<String> = emptyList(),
    // val location: String? = null,
)

// ===== 过滤条件（密封类，编译时类型安全）=====
sealed class TaskFilter {
    object ALL : TaskFilter()
    object ACTIVE : TaskFilter()
    object COMPLETED : TaskFilter()
    object ARCHIVED : TaskFilter()
    data class BY_CATEGORY(val categoryUid: String) : TaskFilter()
}

// ===== 排序方式（密封类，编译时类型安全）=====
enum class TaskSortBy {
    DUE_DATE, CREATED_AT, PRIORITY, TITLE
}
