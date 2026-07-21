package com.deadliner.viewmodel.task

import com.deadliner.db.repository.CategoryRepository
import com.deadliner.db.repository.TaskRepository
import com.deadliner.domain.category.Category
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
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
 * 任务编辑页 ViewModel。
 *
 * 核心设计：
 * 1. 支持创建和编辑两种模式
 * 2. 持有表单状态，支持实时验证
 * 3. 使用配置对象而非多参数，易于扩展
 */
class TaskEditorViewModel(
    private val taskRepository: TaskRepository,
    private val categoryRepository: CategoryRepository,
    private val editingTaskUid: String? = null,  // null 表示创建模式
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val notificationOrchestrator: NotificationOrchestrator? = null,
) {
    // ===== 内存缓存 =====
    private val _uiState = MutableStateFlow(TaskEditorUiState())
    val uiState: StateFlow<TaskEditorUiState> = _uiState.asStateFlow()

    // ===== 初始化 =====
    fun initialize() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val categories = categoryRepository.list()
                val editingTask = editingTaskUid?.let { taskRepository.find(it) }

                _uiState.update {
                    it.copy(
                        categories = categories,
                        editingTask = editingTask,
                        formData = editingTask?.toFormData() ?: TaskFormData(),
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

    // ===== 表单操作 =====

    fun updateTitle(title: String) {
        _uiState.update { it.copy(formData = it.formData.copy(title = title)) }
    }

    fun updateNote(note: String) {
        _uiState.update { it.copy(formData = it.formData.copy(note = note)) }
    }

    fun updateStartAt(startAt: String?) {
        _uiState.update { it.copy(formData = it.formData.copy(startAt = startAt)) }
    }

    fun updateDueAt(dueAt: String?) {
        _uiState.update { it.copy(formData = it.formData.copy(dueAt = dueAt)) }
    }

    fun updateCategory(categoryUid: String?) {
        _uiState.update { it.copy(formData = it.formData.copy(categoryUid = categoryUid)) }
    }

    fun updateIsStarred(isStarred: Boolean) {
        _uiState.update { it.copy(formData = it.formData.copy(isStarred = isStarred)) }
    }

    fun updateCalendarEventId(calendarEventId: Long?) {
        _uiState.update { it.copy(formData = it.formData.copy(calendarEventId = calendarEventId)) }
    }

    // ===== 保存任务 =====
    fun save() {
        scope.launch {
            val formData = _uiState.value.formData
            if (!formData.isValid()) {
                _uiState.update { it.copy(error = "标题不能为空") }
                return@launch
            }

            _uiState.update { it.copy(isSaving = true) }
            try {
                val now = Clock.System.now().toString()
                val task = if (editingTaskUid != null) {
                    // 编辑模式
                    val existing = taskRepository.find(editingTaskUid) ?: return@launch
                    existing.copy(
                        title = formData.title,
                        note = formData.note,
                        startAt = formData.startAt,
                        dueAt = formData.dueAt,
                        categoryUid = formData.categoryUid,
                        isStarred = formData.isStarred,
                        calendarEventId = formData.calendarEventId,
                        updatedAt = now,
                    )
                } else {
                    // 创建模式
                    Task(
                        uid = PlatformIdentity.newId(),
                        title = formData.title,
                        note = formData.note,
                        startAt = formData.startAt,
                        dueAt = formData.dueAt,
                        state = TaskState.ACTIVE,
                        categoryUid = formData.categoryUid,
                        isStarred = formData.isStarred,
                        calendarEventId = formData.calendarEventId,
                        createdAt = now,
                        updatedAt = now,
                    )
                }

                if (editingTaskUid != null) {
                    taskRepository.update(task)
                    notificationOrchestrator?.onTaskUpdated(task)
                } else {
                    taskRepository.create(task)
                    notificationOrchestrator?.onTaskCreated(task)
                }

                _uiState.update {
                    it.copy(
                        savedTask = task,
                        isSaving = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = e.message,
                    )
                }
            }
        }
    }
}

// ===== UiState 定义 =====
data class TaskEditorUiState(
    val categories: List<Category> = emptyList(),
    val editingTask: Task? = null,
    val formData: TaskFormData = TaskFormData(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val savedTask: Task? = null,
    val error: String? = null,
) {
    val isEditing: Boolean get() = editingTask != null
}

// ===== 表单数据（使用数据类，易于扩展）=====
data class TaskFormData(
    val title: String = "",
    val note: String = "",
    val startAt: String? = null,
    val dueAt: String? = null,
    val categoryUid: String? = null,
    val isStarred: Boolean = false,
    val calendarEventId: Long? = null,
    // 未来可轻松添加新字段
    // val tags: List<String> = emptyList(),
    // val location: String? = null,
) {
    fun isValid(): Boolean = title.isNotBlank()
}

// ===== 扩展函数 =====
private fun Task.toFormData(): TaskFormData = TaskFormData(
    title = title,
    note = note,
    startAt = startAt,
    dueAt = dueAt,
    categoryUid = categoryUid,
    isStarred = isStarred,
    calendarEventId = calendarEventId,
)
