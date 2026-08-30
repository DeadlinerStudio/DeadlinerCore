package com.deadliner.viewmodel.habit

import com.deadliner.db.repository.HabitRepository
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitGoalWindow
import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitProgressEngine
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitStatusAction
import com.deadliner.domain.habit.HabitTracking
import com.deadliner.domain.habit.HabitWeekdays
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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
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
                val records = habitRepository.allRecords()
                val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
                _uiState.value = buildState(habits, records, today, _uiState.value.searchQuery)
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

    /** Native pull bridges use the same ViewModel state as flow-based clients. */
    fun loadNow(): HabitListUiState = try {
        val habits = habitRepository.list()
        val records = habitRepository.allRecords()
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        val selectedDate = _uiState.value.selectedDate.ifBlank { today }
        buildState(habits, records, selectedDate, _uiState.value.searchQuery).also { _uiState.value = it }
    } catch (error: Exception) {
        HabitListUiState(isLoading = false, error = error.message).also { _uiState.value = it }
    }

    fun saveHabitNow(habit: Habit): HabitListUiState = try {
        if (habitRepository.find(habit.uid) == null) {
            habitRepository.create(habit)
            notificationOrchestrator?.onHabitCreated(habit)
        } else {
            habitRepository.update(habit)
            notificationOrchestrator?.onHabitUpdated(habit)
        }
        loadNow()
    } catch (error: Exception) {
        _uiState.value.copy(isLoading = false, error = error.message).also { _uiState.value = it }
    }

    fun applyHabitStatusNow(uid: String, action: HabitStatusAction, occurredAt: String): HabitListUiState = try {
        habitRepository.applyStatusAction(uid, action, occurredAt).habit?.let { habit ->
            notificationOrchestrator?.onHabitUpdated(habit)
        }
        loadNow()
    } catch (error: Exception) {
        _uiState.value.copy(isLoading = false, error = error.message).also { _uiState.value = it }
    }

    fun toggleHabitRecordNow(uid: String, occurredOn: String, occurredAt: String): HabitListUiState = try {
        applyPrimaryRecordAction(uid, occurredOn, occurredAt)?.let { habit ->
            notificationOrchestrator?.onHabitRecordsChanged(habit, habitRepository.records(uid))
        }
        loadNow()
    } catch (error: Exception) {
        _uiState.value.copy(isLoading = false, error = error.message).also { _uiState.value = it }
    }

    fun saveHabitRecordNow(record: HabitRecord): HabitListUiState = try {
        habitRepository.saveRecord(record)
        habitRepository.find(record.habitUid)?.let { habit ->
            notificationOrchestrator?.onHabitRecordsChanged(habit, habitRepository.records(record.habitUid))
        }
        loadNow()
    } catch (error: Exception) {
        _uiState.value.copy(isLoading = false, error = error.message).also { _uiState.value = it }
    }

    fun addHabitRecordNow(uid: String, occurredOn: String, amount: Int, occurredAt: String): HabitListUiState = try {
        habitRepository.addRecordAmount(uid, occurredOn, amount, occurredAt).habit?.let { habit ->
            notificationOrchestrator?.onHabitRecordsChanged(habit, habitRepository.records(uid))
        }
        loadNow()
    } catch (error: Exception) {
        _uiState.value.copy(isLoading = false, error = error.message).also { _uiState.value = it }
    }

    fun clearHabitRecordsNow(uid: String, occurredOn: String, occurredAt: String): HabitListUiState = try {
        habitRepository.clearRecords(uid, occurredOn, occurredAt).habit?.let { habit ->
            notificationOrchestrator?.onHabitRecordsChanged(habit, habitRepository.records(uid))
        }
        loadNow()
    } catch (error: Exception) {
        _uiState.value.copy(isLoading = false, error = error.message).also { _uiState.value = it }
    }

    fun deleteHabitNow(uid: String, updatedAt: String): HabitListUiState = try {
        habitRepository.delete(uid, updatedAt)
        notificationOrchestrator?.onHabitDeleted(uid)
        loadNow()
    } catch (error: Exception) {
        _uiState.value.copy(isLoading = false, error = error.message).also { _uiState.value = it }
    }

    /** Changes the calendar focus without moving date aggregation back into platform UI. */
    fun selectDate(date: String) {
        val parsed = runCatching { LocalDate.parse(date) }.getOrElse {
            _uiState.update { it.copy(error = "Invalid habit date: $date") }
            return
        }
        _uiState.value = buildState(
            _uiState.value.habits,
            _uiState.value.records,
            parsed.toString(),
            _uiState.value.searchQuery,
        )
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = buildState(
            _uiState.value.habits,
            _uiState.value.records,
            _uiState.value.selectedDate,
            query,
        )
    }

    fun toggleHabitOnSelectedDate(uid: String) {
        scope.launch {
            try {
                val selectedDate = _uiState.value.selectedDate
                val status = _uiState.value.visibleHabits.firstOrNull { it.habit.uid == uid }
                if (status?.scheduleState?.isDue == false) return@launch
                applyPrimaryRecordAction(uid, selectedDate, Clock.System.now().toString())?.let { habit ->
                    notificationOrchestrator?.onHabitRecordsChanged(habit, habitRepository.records(uid))
                }
                loadNow()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
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
                    schedule = config.schedule,
                    goal = config.goal,
                    tracking = config.tracking,
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
                applyPrimaryRecordAction(uid, today, now)?.let { habit ->
                    notificationOrchestrator?.onHabitRecordsChanged(habit, habitRepository.records(uid))
                    // 更新内存缓存中的习惯
                    _uiState.update { state ->
                        state.copy(
                            habits = state.habits.map { if (it.uid == uid) habit else it },
                            error = null,
                        )
                    }
                    // 重新计算今日完成状态
                    val completedToday = HabitProgressEngine.dayOverview(
                        _uiState.value.habits,
                        habitRepository.allRecords(),
                        LocalDate.parse(today),
                    ).completedByHabitUid
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
                val completedToday = HabitProgressEngine.dayOverview(
                    _uiState.value.habits,
                    habitRepository.allRecords(),
                    LocalDate.parse(today),
                ).completedByHabitUid
                _uiState.update { it.copy(completedToday = completedToday) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message)
                }
            }
        }
    }

    // ===== 辅助方法 =====

    private fun applyPrimaryRecordAction(uid: String, occurredOn: String, occurredAt: String): Habit? {
        return habitRepository.applyPrimaryRecordAction(uid, occurredOn, occurredAt).habit
    }

    private fun buildState(
        habits: List<Habit>,
        records: List<HabitRecord>,
        selectedDate: String,
        query: String,
    ): HabitListUiState {
        val active = habits.filter { it.status == HabitStatus.ACTIVE }
        val statuses = HabitPresentationEngine.statuses(active, records, selectedDate)
            .filter { query.isBlank() || it.habit.name.contains(query, ignoreCase = true) }
        val selected = LocalDate.parse(selectedDate)
        val weekStart = selected.minus(DatePeriod(days = selected.dayOfWeek.ordinal))
        val week = (0..6).map { offset ->
            val date = weekStart.plus(DatePeriod(days = offset))
            val overview = HabitProgressEngine.dayOverview(active, records, date)
            HabitDayOverview(
                date = date.toString(),
                completedCount = overview.completedHabitCount,
                totalCount = overview.dueHabitCount,
            )
        }
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        val todayOverview = HabitProgressEngine.dayOverview(habits, records, LocalDate.parse(today))
        val dailyHabits = statuses.filter {
            it.habit.goal.window == HabitGoalWindow.SCHEDULED_DAY && it.scheduleState?.isDue == true
        }
        val periodGoals = statuses.filter { it.habit.goal.window != HabitGoalWindow.SCHEDULED_DAY }
        val upcomingReviews = statuses.filter {
            it.habit.schedule.kind == HabitScheduleKind.ALGORITHM && it.scheduleState?.isDue == false
        }
        return HabitListUiState(
            habits = habits,
            records = records,
            completedToday = todayOverview.completedByHabitUid,
            selectedDate = selectedDate,
            visibleHabits = dailyHabits + periodGoals,
            dailyHabits = dailyHabits,
            periodGoals = periodGoals,
            upcomingReviews = upcomingReviews,
            weekOverview = week,
            searchQuery = query,
            isLoading = false,
        )
    }

}

// ===== UiState 定义（使用数据类，易于扩展）=====
data class HabitListUiState(
    val habits: List<Habit> = emptyList(),
    val records: List<HabitRecord> = emptyList(),
    val completedToday: Map<String, Boolean> = emptyMap(),
    val selectedDate: String = "",
    val visibleHabits: List<HabitDayStatus> = emptyList(),
    val dailyHabits: List<HabitDayStatus> = emptyList(),
    val periodGoals: List<HabitDayStatus> = emptyList(),
    val upcomingReviews: List<HabitDayStatus> = emptyList(),
    val weekOverview: List<HabitDayOverview> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val filter: HabitFilter = HabitFilter.ALL,
)

data class HabitDayOverview(
    val date: String,
    val completedCount: Int,
    val totalCount: Int,
) {
    val completionRatio: Float
        get() = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount
}

// ===== 配置对象（易于扩展新参数）=====
data class HabitCreateConfig(
    val name: String,
    val description: String? = null,
    val color: Int? = null,
    val iconKey: String? = null,
    val categoryUid: String? = null,
    val schedule: HabitSchedule = HabitSchedule(
        HabitScheduleKind.WEEKLY_DAYS,
        "1970-01-01",
        weekdaysMask = HabitWeekdays.ALL,
    ),
    val goal: HabitGoal = HabitGoal(),
    val tracking: HabitTracking = HabitTracking(),
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
