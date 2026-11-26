package com.deadliner.platform

/** Policy has fixed business timing; native hosts only choose enabled surfaces. */
data class NotificationPolicy(
    val taskRealtime: Boolean = true,
    val taskNearby: Boolean = true,
    val taskDue: Boolean = true,
    val habitMode: HabitNotificationMode = HabitNotificationMode.DUE,
)
enum class HabitNotificationMode { REALTIME, DUE }
enum class NotificationKind { TASK_NEARBY, TASK_DUE, HABIT_DUE, HABIT_START, TASK_REALTIME, HABIT_REALTIME, FOCUS_RESULT }
enum class NotificationPhase { UPCOMING, OVERDUE, COMPLETED, ABANDONED, HABIT_DUE, HABIT_CHECKED_IN, FOCUS_FINISHED }
enum class NotificationAction { COMPLETE, GIVE_UP, CHECK_IN, IGNORE, RESTORE_ACTIVE, OPEN_TASK, OPEN_FOCUS }

data class NotificationMetadataEntry(
    val key: String,
    val ownerUid: String,
    val version: String,
    val title: String,
    val kind: NotificationKind,
    val triggerAt: String,
    val endsAt: String? = null,
    val phase: NotificationPhase? = null,
    val actions: List<NotificationAction> = emptyList(),
    val occurrenceOn: String? = null,
    val dueAt: String = triggerAt,
    val habitWindow: com.deadliner.domain.habit.HabitGoalWindow? = null,
    val completedCount: Int? = null,
    val targetCount: Int? = null,
    val focusSessionUid: String? = null,
    val focusDurationSeconds: Long? = null,
)
data class NotificationMetadata(
    val alarms: List<NotificationMetadataEntry>,
    val realtime: List<NotificationMetadataEntry>,
    val nextRefreshAt: String?,
    val activeVersions: Map<String, String> = emptyMap(),
    val ordinaryKinds: Set<NotificationKind> = emptySet(),
)
data class TaskNotificationResult(val taskUid: String, val version: String, val phase: NotificationPhase, val occurredAt: String)
data class NotificationInteractionState(
    val taskResults: List<TaskNotificationResult> = emptyList(),
    val dismissedHabitOccurrences: Set<String> = emptySet(),
)
