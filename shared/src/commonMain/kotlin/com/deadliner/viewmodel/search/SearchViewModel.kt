package com.deadliner.viewmodel.search

import com.deadliner.db.repository.HabitRepository
import com.deadliner.db.repository.TaskRepository
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.task.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 搜索页 ViewModel。
 *
 * 核心设计：
 * 1. 支持搜索任务和习惯
 * 2. 使用内存缓存，零 IO 成本
 * 3. 支持实时搜索
 */
class SearchViewModel(
    private val taskRepository: TaskRepository,
    private val habitRepository: HabitRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
) {
    // ===== 内存缓存 =====
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    // ===== 初始化 =====
    fun initialize() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val tasks = taskRepository.list()
                val habits = habitRepository.list()
                _uiState.update {
                    it.copy(
                        allTasks = tasks,
                        allHabits = habits,
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

    // ===== 搜索 =====
    fun search(query: String) {
        _uiState.update { it.copy(query = query) }
        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    matchedTasks = emptyList(),
                    matchedHabits = emptyList(),
                )
            }
            return
        }

        val lowerQuery = query.lowercase()
        val matchedTasks = _uiState.value.allTasks.filter { task ->
            task.title.lowercase().contains(lowerQuery) ||
                task.note.lowercase().contains(lowerQuery)
        }
        val matchedHabits = _uiState.value.allHabits.filter { habit ->
            habit.name.lowercase().contains(lowerQuery) ||
                (habit.description?.lowercase()?.contains(lowerQuery) ?: false)
        }

        _uiState.update {
            it.copy(
                matchedTasks = matchedTasks,
                matchedHabits = matchedHabits,
            )
        }
    }

    // ===== 清除搜索 =====
    fun clearSearch() {
        _uiState.update {
            it.copy(
                query = "",
                matchedTasks = emptyList(),
                matchedHabits = emptyList(),
            )
        }
    }
}

// ===== UiState 定义 =====
data class SearchUiState(
    val allTasks: List<Task> = emptyList(),
    val allHabits: List<Habit> = emptyList(),
    val matchedTasks: List<Task> = emptyList(),
    val matchedHabits: List<Habit> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
)
