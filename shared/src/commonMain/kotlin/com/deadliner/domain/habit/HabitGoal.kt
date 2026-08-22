package com.deadliner.domain.habit

import kotlinx.serialization.Serializable

@Serializable
data class HabitGoal(
    val window: HabitGoalWindow = HabitGoalWindow.SCHEDULED_DAY,
    val targetCount: Int = 1,
) {
    init {
        require(targetCount > 0) { "Habit goal targetCount must be positive" }
    }
}

@Serializable
enum class HabitGoalWindow {
    SCHEDULED_DAY,
    WEEK,
    MONTH,
    LIFETIME,
}

@Serializable
data class HabitTracking(
    val mode: HabitTrackingMode = HabitTrackingMode.CHECK_OFF,
    val unit: String? = null,
    val defaultIncrement: Int = 1,
) {
    init {
        require(defaultIncrement > 0) { "Habit defaultIncrement must be positive" }
    }
}

@Serializable
enum class HabitTrackingMode {
    CHECK_OFF,
    QUANTITY,
}
