package com.deadliner.viewmodel.habit

import com.deadliner.db.repository.HabitRepository
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitGoalType
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitRecordStatus
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitStatusAction
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
import kotlinx.datetime.daysUntil
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
        habitRepository.toggleRecord(uid, occurredOn, occurredAt)
        loadNow()
    } catch (error: Exception) {
        _uiState.value.copy(isLoading = false, error = error.message).also { _uiState.value = it }
    }

    fun saveHabitRecordNow(record: HabitRecord): HabitListUiState = try {
        habitRepository.saveRecord(record)
        loadNow()
    } catch (error: Exception) {
        _uiState.value.copy(isLoading = false, error = error.message).also { _uiState.value = it }
    }

    fun clearHabitRecordsNow(uid: String, occurredOn: String, occurredAt: String): HabitListUiState = try {
        habitRepository.clearRecords(uid, occurredOn, occurredAt)
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
                habitRepository.toggleRecord(uid, selectedDate, Clock.System.now().toString())
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
            val todayRecords = habitRepository.records(habit.uid).filter {
                it.occurredOn == today && it.status == HabitRecordStatus.COMPLETED
            }
            val isCompleted = when (habit.period) {
                HabitPeriod.ONCE, HabitPeriod.EBBINGHAUS -> todayRecords.isNotEmpty()
                HabitPeriod.DAILY, HabitPeriod.WEEKLY, HabitPeriod.MONTHLY ->
                    todayRecords.sumOf { it.count } >= habit.timesPerPeriod
            }
            habit.uid to isCompleted
        }
    }

    private fun calculateCompletedToday(habits: List<Habit>, records: List<HabitRecord>, today: String): Map<String, Boolean> {
        return habits.associate { habit ->
            val todayRecords = records.filter {
                it.habitUid == habit.uid &&
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

    private fun buildState(
        habits: List<Habit>,
        records: List<HabitRecord>,
        selectedDate: String,
        query: String,
    ): HabitListUiState {
        val active = habits.filter { it.status == HabitStatus.ACTIVE }
        val statuses = calculateStatuses(active, records, selectedDate)
            .filter { query.isBlank() || it.habit.name.contains(query, ignoreCase = true) }
        val selected = LocalDate.parse(selectedDate)
        val weekStart = selected.minus(DatePeriod(days = selected.dayOfWeek.ordinal))
        val week = (0..6).map { offset ->
            val date = weekStart.plus(DatePeriod(days = offset))
            val dayStatuses = calculateStatuses(active, records, date.toString())
                .filter { it.scheduleState?.isDue != false }
            HabitDayOverview(
                date = date.toString(),
                completedCount = dayStatuses.count(HabitDayStatus::isCompleted),
                totalCount = dayStatuses.size,
            )
        }
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        return HabitListUiState(
            habits = habits,
            records = records,
            completedToday = calculateCompletedToday(habits, records, today),
            selectedDate = selectedDate,
            visibleHabits = statuses,
            weekOverview = week,
            searchQuery = query,
            isLoading = false,
        )
    }

    private fun calculateStatuses(
        habits: List<Habit>,
        records: List<HabitRecord>,
        rawDate: String,
    ): List<HabitDayStatus> {
        val date = LocalDate.parse(rawDate)
        return habits.map { habit ->
            val schedule = habit.scheduleState(date)
            val completedRecords = records.filter {
                !it.isDeleted && it.habitUid == habit.uid && it.status == HabitRecordStatus.COMPLETED
            }
            val periodRecords = when (habit.goalType) {
                HabitGoalType.TOTAL -> completedRecords.filter { it.occurredOn <= rawDate }
                HabitGoalType.PER_PERIOD -> completedRecords.filter { it.occurredOn in periodRange(habit.period, date) }
            }
            val done = periodRecords.sumOf(HabitRecord::count)
            val target = when {
                habit.goalType == HabitGoalType.TOTAL -> habit.totalTarget?.coerceAtLeast(1) ?: done.coerceAtLeast(1)
                habit.period == HabitPeriod.EBBINGHAUS -> 1
                else -> habit.timesPerPeriod.coerceAtLeast(1)
            }
            HabitDayStatus(habit, done, target, done >= target, schedule)
        }
    }

    private fun periodRange(period: HabitPeriod, date: LocalDate): ClosedRange<String> {
        val start = when (period) {
            HabitPeriod.WEEKLY -> date.minus(DatePeriod(days = date.dayOfWeek.ordinal))
            HabitPeriod.MONTHLY -> LocalDate(date.year, date.month, 1)
            else -> date
        }
        val end = when (period) {
            HabitPeriod.WEEKLY -> start.plus(DatePeriod(days = 6))
            HabitPeriod.MONTHLY -> start.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
            else -> start
        }
        return start.toString()..end.toString()
    }

    private fun Habit.scheduleState(date: LocalDate): HabitScheduleUiState? {
        if (period != HabitPeriod.EBBINGHAUS) return null
        val createdDate = runCatching {
            kotlinx.datetime.Instant.parse(createdAt).toLocalDateTime(TimeZone.currentSystemDefault()).date
        }.getOrElse { LocalDate.parse(createdAt.substringBefore('T')) }
        val elapsed = createdDate.daysUntil(date)
        if (date < createdDate) {
            return HabitScheduleUiState(false, HabitScheduleHintKind.STARTS_IN, date.daysUntil(createdDate))
        }
        val reviewDays = listOf(0, 1, 2, 4, 7, 15, 30, 60)
        if (elapsed in reviewDays) return HabitScheduleUiState(true)
        val next = reviewDays.firstOrNull { it > elapsed }
        return if (next == null) {
            HabitScheduleUiState(false, HabitScheduleHintKind.COMPLETED_CYCLE)
        } else {
            HabitScheduleUiState(false, HabitScheduleHintKind.NEXT_REVIEW_IN, next - elapsed)
        }
    }
}

// ===== UiState 定义（使用数据类，易于扩展）=====
data class HabitListUiState(
    val habits: List<Habit> = emptyList(),
    val records: List<HabitRecord> = emptyList(),
    val completedToday: Map<String, Boolean> = emptyMap(),
    val selectedDate: String = "",
    val visibleHabits: List<HabitDayStatus> = emptyList(),
    val weekOverview: List<HabitDayOverview> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val filter: HabitFilter = HabitFilter.ALL,
)

data class HabitDayStatus(
    val habit: Habit,
    val doneCount: Int,
    val targetCount: Int,
    val isCompleted: Boolean,
    val scheduleState: HabitScheduleUiState? = null,
)

data class HabitDayOverview(
    val date: String,
    val completedCount: Int,
    val totalCount: Int,
) {
    val completionRatio: Float
        get() = if (totalCount == 0) 0f else completedCount.toFloat() / totalCount
}

data class HabitScheduleUiState(
    val isDue: Boolean,
    val hintKind: HabitScheduleHintKind? = null,
    val daysUntil: Int? = null,
)

enum class HabitScheduleHintKind { STARTS_IN, NEXT_REVIEW_IN, COMPLETED_CYCLE }

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
