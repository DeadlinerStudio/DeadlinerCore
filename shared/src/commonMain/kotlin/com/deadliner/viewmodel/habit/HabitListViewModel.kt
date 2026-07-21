package com.deadliner.viewmodel.habit

import com.deadliner.db.repository.HabitRepository
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecordStatus
import com.deadliner.domain.habit.HabitStatus
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 习惯列表页 ViewModel。
 *
 * 核心设计：
 * 1. 持有内存缓存 StateFlow，UI 直接订阅，零 IO 成本
 * 2. 写操作后同步更新内存缓存，无需重新查询 DB
 * 3. 使用配置对象而非多参数，易于扩展
 */
class HabitListViewModel(
    private val habitRepository: HabitRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val notificationOrchestrator: NotificationOrchestrator? = null,
) {
    // ===== 内存缓存（IO 优化核心）=====
    private val _uiState = MutableStateFlow(HabitListUiState())
    val uiState: StateFlow<HabitListUiState> = _uiState.asStateFlow()

    // ===== 初始化（从 DB 加载到内存缓存）=====
    fun initialize() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val habits = habitRepository.list()
                val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
                val completedToday = calculateCompletedToday(habits, today)
                _uiState.update {
                    it.copy(
                        habits = habits,
                        completedToday = completedToday,
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

    fun createHabit(config: HabitCreateConfig) {
        scope.launch {
            try {
                val habit = Habit(
                    uid = PlatformIdentity.newId(),
                    name = config.name,
                    description = config.description,
                    color = config.color,
                    iconKey = config.iconKey,
                    categoryUid = config.categoryUid,
                    period = config.period,
                    timesPerPeriod = config.timesPerPeriod,
                    goalType = config.goalType,
                    totalTarget = config.totalTarget,
                    status = HabitStatus.ACTIVE,
                    sortOrder = config.sortOrder,
                    reminder = config.reminder,
                    createdAt = Clock.System.now().toString(),
                    updatedAt = Clock.System.now().toString(),
                )
                habitRepository.create(habit)
                notificationOrchestrator?.onHabitCreated(habit)
                // 直接更新内存缓存，无需重新查询 DB
                _uiState.update { state ->
                    state.copy(
                        habits = state.habits + habit,
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

    fun toggleHabit(uid: String) {
        scope.launch {
            try {
                val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
                val now = Clock.System.now().toString()
                val result = habitRepository.toggleRecord(uid, today, now)
                result.habit?.let { habit ->
                    // 更新内存缓存中的习惯
                    _uiState.update { state ->
                        state.copy(
                            habits = state.habits.map { if (it.uid == uid) habit else it },
                            error = null,
                        )
                    }
                    // 重新计算今日完成状态
                    val completedToday = calculateCompletedToday(_uiState.value.habits, today)
                    _uiState.update { it.copy(completedToday = completedToday) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message)
                }
            }
        }
    }

    fun deleteHabit(uid: String) {
        scope.launch {
            try {
                habitRepository.delete(uid, Clock.System.now().toString())
                notificationOrchestrator?.onHabitDeleted(uid)
                // 从内存缓存中移除
                _uiState.update { state ->
                    state.copy(
                        habits = state.habits.filter { it.uid != uid },
                        error = null,
                    )
                }
                // 重新计算今日完成状态
                val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
                val completedToday = calculateCompletedToday(_uiState.value.habits, today)
                _uiState.update { it.copy(completedToday = completedToday) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message)
                }
            }
        }
    }

    // ===== 辅助方法 =====

    private fun calculateCompletedToday(habits: List<Habit>, today: String): Map<String, Boolean> {
        return habits.associate { habit ->
            val records = habitRepository.records(habit.uid)
            val todayRecords = records.filter {
                it.occurredOn == today && it.status == HabitRecordStatus.COMPLETED
            }
            val isCompleted = when (habit.period) {
                HabitPeriod.ONCE, HabitPeriod.EBBINGHAUS -> todayRecords.isNotEmpty()
                HabitPeriod.DAILY -> todayRecords.sumOf { it.count } >= habit.timesPerPeriod.coerceAtLeast(1)
                HabitPeriod.WEEKLY, HabitPeriod.MONTHLY -> todayRecords.isNotEmpty()
            }
            habit.uid to isCompleted
        }
    }
}

// ===== UiState 定义（使用数据类，易于扩展）=====
data class HabitListUiState(
    val habits: List<Habit> = emptyList(),
    val completedToday: Map<String, Boolean> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val filter: HabitFilter = HabitFilter.ALL,
)

// ===== 配置对象（易于扩展新参数）=====
data class HabitCreateConfig(
    val name: String,
    val description: String? = null,
    val color: Int? = null,
    val iconKey: String? = null,
    val categoryUid: String? = null,
    val period: HabitPeriod = HabitPeriod.DAILY,
    val timesPerPeriod: Int = 1,
    val goalType: com.deadliner.domain.habit.HabitGoalType = com.deadliner.domain.habit.HabitGoalType.PER_PERIOD,
    val totalTarget: Int? = null,
    val sortOrder: Int = 0,
    val reminder: com.deadliner.domain.habit.HabitReminder? = null,
    // 未来可轻松添加新字段
    // val tags: List<String> = emptyList(),
)

// ===== 过滤条件（密封类，编译时类型安全）=====
sealed class HabitFilter {
    object ALL : HabitFilter()
    object ACTIVE : HabitFilter()
    object ARCHIVED : HabitFilter()
    data class BY_CATEGORY(val categoryUid: String) : HabitFilter()
}
