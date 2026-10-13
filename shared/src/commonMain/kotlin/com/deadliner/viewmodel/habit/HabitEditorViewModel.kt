package com.deadliner.viewmodel.habit

import com.deadliner.db.repository.CategoryRepository
import com.deadliner.db.repository.HabitRepository
import com.deadliner.domain.category.Category
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitGoalType
import com.deadliner.domain.habit.HabitAlgorithms
import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitGoalWindow
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitTracking
import com.deadliner.domain.habit.HabitTrackingMode
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class HabitEditorViewModel(
    private val habitRepository: HabitRepository,
    private val categoryRepository: CategoryRepository,
    private val editingHabitUid: String? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val notificationOrchestrator: NotificationOrchestrator? = null,
) {
    private val _uiState = MutableStateFlow(HabitEditorUiState(
        formData = editingHabitUid?.let(habitRepository::find)?.toFormData() ?: HabitFormData(),
    ))
    val uiState: StateFlow<HabitEditorUiState> = _uiState.asStateFlow()

    fun initialize() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val habit = editingHabitUid?.let(habitRepository::find)
                _uiState.value = HabitEditorUiState(
                    categories = categoryRepository.list(),
                    editingHabit = habit,
                    formData = habit?.toFormData() ?: HabitFormData(
                        schedule = HabitSchedule(
                            HabitScheduleKind.WEEKLY_DAYS,
                            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString(),
                            weekdaysMask = HabitWeekdays.ALL,
                        ),
                    ),
                    isLoading = false,
                )
            } catch (error: Exception) {
                _uiState.update { it.copy(isLoading = false, error = error.message) }
            }
        }
    }

    fun updateName(value: String) = updateForm { copy(name = value) }
    fun updateDescription(value: String?) = updateForm { copy(description = value) }
    fun updateColor(value: Int?) = updateForm { copy(color = value) }
    fun updateIconKey(value: String?) = updateForm { copy(iconKey = value) }
    fun updateCategory(value: String?) = updateForm { copy(categoryUid = value) }
    fun updateSchedule(value: HabitSchedule) = updateForm { copy(schedule = value) }
    fun updateGoal(value: HabitGoal) = updateForm { copy(goal = value) }
    fun updateTracking(value: HabitTracking) = updateForm { copy(tracking = value) }
    fun updateWeekdaysMask(value: Int) = updateForm {
        copy(schedule = schedule.copy(kind = HabitScheduleKind.WEEKLY_DAYS, weekdaysMask = value, algorithmKey = null))
    }

    /** Temporary source-compatible adapter for Android's existing editor wiring. */
    fun updatePeriod(value: HabitPeriod) = updateForm {
        when (value) {
            HabitPeriod.ONCE -> copy(
                schedule = HabitSchedule(HabitScheduleKind.ONCE, schedule.startOn, schedule.endOn),
                goal = goal.copy(window = HabitGoalWindow.SCHEDULED_DAY),
            )
            HabitPeriod.EBBINGHAUS -> copy(
                schedule = HabitSchedule(
                    HabitScheduleKind.ALGORITHM,
                    schedule.startOn,
                    schedule.endOn,
                    algorithmKey = HabitAlgorithms.EBBINGHAUS_CLASSIC_V1,
                ),
                goal = HabitGoal(HabitGoalWindow.SCHEDULED_DAY, 1),
                tracking = HabitTracking(HabitTrackingMode.CHECK_OFF),
            )
            else -> copy(
                schedule = HabitSchedule(
                    HabitScheduleKind.WEEKLY_DAYS,
                    schedule.startOn,
                    schedule.endOn,
                    weekdaysMask = schedule.weekdaysMask ?: HabitWeekdays.ALL,
                ),
                goal = goal.copy(
                    window = when (value) {
                        HabitPeriod.WEEKLY -> HabitGoalWindow.WEEK
                        HabitPeriod.MONTHLY -> HabitGoalWindow.MONTH
                        else -> HabitGoalWindow.SCHEDULED_DAY
                    },
                ),
            )
        }
    }
    fun updateTimesPerPeriod(value: Int) = updateForm {
        if (goal.window == HabitGoalWindow.LIFETIME) this else copy(goal = goal.copy(targetCount = value.coerceAtLeast(1)))
    }
    fun updateGoalType(value: HabitGoalType) = updateForm {
        copy(goal = goal.copy(window = if (value == HabitGoalType.TOTAL) HabitGoalWindow.LIFETIME else period.defaultWindow()))
    }
    fun updateTotalTarget(value: Int?) = updateForm {
        if (goal.window == HabitGoalWindow.LIFETIME && value != null) copy(goal = goal.copy(targetCount = value.coerceAtLeast(1))) else this
    }
    fun updateReminder(value: HabitReminder?) = updateForm { copy(reminder = value) }

    fun save() {
        scope.launch {
            val form = _uiState.value.formData
            if (!form.isValid()) {
                _uiState.update { it.copy(error = "名称不能为空") }
                return@launch
            }
            _uiState.update { it.copy(isSaving = true) }
            try {
                val now = Clock.System.now().toString()
                val existing = editingHabitUid?.let(habitRepository::find)
                check(editingHabitUid == null || existing != null) { "Habit to edit no longer exists" }
                check(existing?.isDeleted != true) { "Habit to edit was deleted" }
                val habit = Habit(
                    uid = existing?.uid ?: PlatformIdentity.newId(),
                    name = form.name,
                    description = form.description,
                    color = form.color,
                    iconKey = form.iconKey,
                    categoryUid = form.categoryUid,
                    schedule = form.schedule,
                    goal = form.goal,
                    tracking = form.tracking,
                    status = existing?.status ?: HabitStatus.ACTIVE,
                    sortOrder = existing?.sortOrder ?: 0,
                    reminder = form.reminder,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
                if (existing == null) {
                    habitRepository.create(habit)
                    notificationOrchestrator?.onHabitCreated(habit)
                } else {
                    habitRepository.update(habit)
                    notificationOrchestrator?.onHabitUpdated(habit)
                }
                _uiState.update { it.copy(savedHabit = habit, isSaving = false, error = null) }
            } catch (error: Exception) {
                _uiState.update { it.copy(isSaving = false, error = error.message) }
            }
        }
    }

    private fun updateForm(transform: HabitFormData.() -> HabitFormData) {
        _uiState.update { it.copy(formData = it.formData.transform()) }
    }
}

data class HabitEditorUiState(
    val categories: List<Category> = emptyList(),
    val editingHabit: Habit? = null,
    val formData: HabitFormData = HabitFormData(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val savedHabit: Habit? = null,
    val error: String? = null,
)

data class HabitFormData(
    val name: String = "",
    val description: String? = null,
    val color: Int? = null,
    val iconKey: String? = null,
    val categoryUid: String? = null,
    val schedule: HabitSchedule = HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, "1970-01-01", weekdaysMask = HabitWeekdays.ALL),
    val goal: HabitGoal = HabitGoal(),
    val tracking: HabitTracking = HabitTracking(),
    val reminder: HabitReminder? = null,
) {
    val period: HabitPeriod
        get() = when {
            schedule.kind == HabitScheduleKind.ONCE -> HabitPeriod.ONCE
            schedule.algorithmKey == HabitAlgorithms.EBBINGHAUS_CLASSIC_V1 -> HabitPeriod.EBBINGHAUS
            goal.window == HabitGoalWindow.WEEK -> HabitPeriod.WEEKLY
            goal.window == HabitGoalWindow.MONTH -> HabitPeriod.MONTHLY
            else -> HabitPeriod.DAILY
        }
    val timesPerPeriod: Int get() = goal.targetCount
    val goalType: HabitGoalType get() = if (goal.window == HabitGoalWindow.LIFETIME) HabitGoalType.TOTAL else HabitGoalType.PER_PERIOD
    val totalTarget: Int? get() = goal.targetCount.takeIf { goal.window == HabitGoalWindow.LIFETIME }

    fun isValid(): Boolean = name.isNotBlank() && goal.targetCount > 0
}

private fun Habit.toFormData() = HabitFormData(
    name = name,
    description = description,
    color = color,
    iconKey = iconKey,
    categoryUid = categoryUid,
    schedule = schedule,
    goal = goal,
    tracking = tracking,
    reminder = reminder,
)

private fun HabitPeriod.defaultWindow(): HabitGoalWindow = when (this) {
    HabitPeriod.WEEKLY -> HabitGoalWindow.WEEK
    HabitPeriod.MONTHLY -> HabitGoalWindow.MONTH
    else -> HabitGoalWindow.SCHEDULED_DAY
}
