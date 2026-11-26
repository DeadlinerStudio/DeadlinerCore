package com.deadliner.viewmodel.search

import com.deadliner.db.repository.HabitRepository
import com.deadliner.db.repository.TaskRepository
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.task.Task
import com.deadliner.viewmodel.habit.HabitDayStatus
import com.deadliner.viewmodel.habit.HabitPresentationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

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
                val records = habitRepository.allRecords()
                _uiState.update {
                    val matches = match(it.query, tasks, habits)
                    it.copy(
                        allTasks = tasks,
                        allHabits = habits,
                        allHabitRecords = records,
                        matchedTasks = matches.first,
                        matchedHabits = matches.second,
                        matchedHabitStatuses = project(matches.second, records),
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
                    matchedHabitStatuses = emptyList(),
                )
            }
            return
        }

        val (matchedTasks, matchedHabits) = match(
            query,
            _uiState.value.allTasks,
            _uiState.value.allHabits,
        )

        _uiState.update {
            it.copy(
                matchedTasks = matchedTasks,
                matchedHabits = matchedHabits,
                matchedHabitStatuses = project(matchedHabits, it.allHabitRecords),
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
                matchedHabitStatuses = emptyList(),
            )
        }
    }

    private fun match(
        query: String,
        tasks: List<Task>,
        habits: List<Habit>,
    ): Pair<List<Task>, List<Habit>> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList<Task>() to emptyList()
        val matchedTasks = tasks.filter { task ->
            task.title.lowercase().contains(normalized) ||
                task.note.lowercase().contains(normalized)
        }
        val matchedHabits = habits.filter { habit ->
            habit.name.lowercase().contains(normalized) ||
                (habit.description?.lowercase()?.contains(normalized) ?: false)
        }
        return matchedTasks to matchedHabits
    }

    private fun project(habits: List<Habit>, records: List<HabitRecord>): List<HabitDayStatus> {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        return HabitPresentationEngine.statuses(habits, records, today)
    }
}

// ===== UiState 定义 =====
data class SearchUiState(
    val allTasks: List<Task> = emptyList(),
    val allHabits: List<Habit> = emptyList(),
    val allHabitRecords: List<HabitRecord> = emptyList(),
    val matchedTasks: List<Task> = emptyList(),
    val matchedHabits: List<Habit> = emptyList(),
    val matchedHabitStatuses: List<HabitDayStatus> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
)
