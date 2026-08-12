package com.deadliner.viewmodel.habit

import com.deadliner.db.repository.CategoryRepository
import com.deadliner.db.repository.HabitRepository
import com.deadliner.domain.category.Category
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitGoalType
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitReminder
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

class HabitEditorViewModel(
    private val habitRepository: HabitRepository,
    private val categoryRepository: CategoryRepository,
    private val editingHabitUid: String? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    private val notificationOrchestrator: NotificationOrchestrator? = null,
) {
    private val _uiState = MutableStateFlow(HabitEditorUiState())
    val uiState: StateFlow<HabitEditorUiState> = _uiState.asStateFlow()

    fun initialize() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val habit = editingHabitUid?.let(habitRepository::find)
                _uiState.value = HabitEditorUiState(
                    categories = categoryRepository.list(),
                    editingHabit = habit,
                    formData = habit?.toFormData() ?: HabitFormData(),
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
    fun updatePeriod(value: HabitPeriod) = updateForm { copy(period = value) }
    fun updateTimesPerPeriod(value: Int) = updateForm { copy(timesPerPeriod = value.coerceAtLeast(1)) }
    fun updateGoalType(value: HabitGoalType) = updateForm { copy(goalType = value) }
    fun updateTotalTarget(value: Int?) = updateForm { copy(totalTarget = value) }
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
                val habit = Habit(
                    uid = existing?.uid ?: PlatformIdentity.newId(),
                    name = form.name,
                    description = form.description,
                    color = form.color,
                    iconKey = form.iconKey,
                    categoryUid = form.categoryUid,
                    period = form.period,
                    timesPerPeriod = form.timesPerPeriod,
                    goalType = form.goalType,
                    totalTarget = form.totalTarget,
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
    val period: HabitPeriod = HabitPeriod.DAILY,
    val timesPerPeriod: Int = 1,
    val goalType: HabitGoalType = HabitGoalType.PER_PERIOD,
    val totalTarget: Int? = null,
    val reminder: HabitReminder? = null,
) {
    fun isValid(): Boolean = name.isNotBlank() && timesPerPeriod > 0 &&
        (goalType != HabitGoalType.TOTAL || totalTarget == null || totalTarget > 0)
}

private fun Habit.toFormData() = HabitFormData(
    name = name,
    description = description,
    color = color,
    iconKey = iconKey,
    categoryUid = categoryUid,
    period = period,
    timesPerPeriod = timesPerPeriod,
    goalType = goalType,
    totalTarget = totalTarget,
    reminder = reminder,
)
