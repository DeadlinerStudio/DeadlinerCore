package com.deadliner.viewmodel.overview

import com.deadliner.db.repository.HabitRepository
import com.deadliner.db.repository.TaskRepository
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecordStatus
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
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
 * 概览页 ViewModel。
 *
 * 核心设计：
 * 1. 持有内存缓存 StateFlow，UI 直接订阅，零 IO 成本
 * 2. 聚合 Task 和 Habit 数据，计算统计信息
 * 3. 使用配置对象而非多参数，易于扩展
 */
class OverviewViewModel(
    private val taskRepository: TaskRepository,
    private val habitRepository: HabitRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
) {
    // ===== 内存缓存（IO 优化核心）=====
    private val _uiState = MutableStateFlow(OverviewUiState())
    val uiState: StateFlow<OverviewUiState> = _uiState.asStateFlow()

    // ===== 初始化（从 DB 加载到内存缓存）=====
    fun initialize() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val tasks = taskRepository.list()
                val habits = habitRepository.list()
                val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

                val stats = calculateStats(tasks, habits, today)
                val upcomingTasks = tasks
                    .filter { it.state == TaskState.ACTIVE && it.dueAt != null }
                    .sortedBy { it.dueAt }
                    .take(5)

                _uiState.update {
                    it.copy(
                        stats = stats,
                        upcomingTasks = upcomingTasks,
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

    // ===== 刷新数据 =====
    fun refresh() {
        initialize()
    }

    // ===== 辅助方法 =====

    private fun calculateStats(
        tasks: List<Task>,
        habits: List<Habit>,
        today: String,
    ): OverviewStats {
        val activeTasks = tasks.count { it.state == TaskState.ACTIVE }
        val completedTasks = tasks.count { it.state == TaskState.COMPLETED }
        val archivedTasks = tasks.count { it.state == TaskState.ARCHIVED }
        val totalTasks = tasks.size

        val activeHabits = habits.count { it.status == com.deadliner.domain.habit.HabitStatus.ACTIVE }
        val habitProgress = habits
            .filter { it.status == com.deadliner.domain.habit.HabitStatus.ACTIVE }
            .map { habit ->
                val records = habitRepository.records(habit.uid)
                val todayRecords = records.filter {
                    it.occurredOn == today && it.status == HabitRecordStatus.COMPLETED
                }
                val currentCount = todayRecords.sumOf { it.count }
                HabitProgress(
                    habit = habit,
                    currentCount = currentCount,
                    targetCount = habit.timesPerPeriod.coerceAtLeast(1),
                    isCompleted = currentCount >= habit.timesPerPeriod.coerceAtLeast(1),
                )
            }

        val completedHabitsToday = habitProgress.count { it.isCompleted }

        return OverviewStats(
            activeTasks = activeTasks,
            completedTasks = completedTasks,
            archivedTasks = archivedTasks,
            totalTasks = totalTasks,
            activeHabits = activeHabits,
            completedHabitsToday = completedHabitsToday,
            habitProgress = habitProgress,
        )
    }
}

// ===== UiState 定义（使用数据类，易于扩展）=====
data class OverviewUiState(
    val stats: OverviewStats = OverviewStats(),
    val upcomingTasks: List<Task> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

// ===== 统计数据（使用数据类，易于扩展）=====
data class OverviewStats(
    val activeTasks: Int = 0,
    val completedTasks: Int = 0,
    val archivedTasks: Int = 0,
    val totalTasks: Int = 0,
    val activeHabits: Int = 0,
    val completedHabitsToday: Int = 0,
    val habitProgress: List<HabitProgress> = emptyList(),
)

// ===== 习惯进度（使用数据类，易于扩展）=====
data class HabitProgress(
    val habit: Habit,
    val currentCount: Int,
    val targetCount: Int,
    val isCompleted: Boolean,
)
