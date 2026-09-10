package com.deadliner.domain.focus

import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

@Serializable
enum class FocusTimerMode {
    STOPWATCH,
    COUNTDOWN,
}

@Serializable
enum class FocusTimerState {
    RUNNING,
    PAUSED,
}

@Serializable
enum class FocusCompletionKind {
    FOCUS_ONLY,
    FOCUS_AND_TASK_COMPLETED,
    COUNTDOWN_ELAPSED,
    TASK_BECAME_INACTIVE,
}

@Serializable
data class FocusSession(
    val uid: String,
    val taskUid: String,
    val taskTitleSnapshot: String,
    val mode: FocusTimerMode,
    val plannedDurationSeconds: Long? = null,
    val startedAt: String,
    val endedAt: String,
    val durationSeconds: Long,
    val completionKind: FocusCompletionKind,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val isDeleted: Boolean = false,
) {
    init {
        require(uid.isNotBlank()) { "Focus session uid must not be blank" }
        require(taskUid.isNotBlank()) { "Focus session taskUid must not be blank" }
        require(taskTitleSnapshot.isNotBlank()) { "Focus session task title must not be blank" }
        require(durationSeconds > 0) { "Focus session duration must be positive" }
        validatePlannedDuration(mode, plannedDurationSeconds)

        val started = parseInstant(startedAt, "startedAt")
        val ended = parseInstant(endedAt, "endedAt")
        parseInstant(createdAt, "createdAt")
        parseInstant(updatedAt, "updatedAt")
        require(ended >= started) { "Focus session endedAt must not precede startedAt" }
        require(durationSeconds <= (ended - started).inWholeSeconds) {
            "Focus session duration must not exceed its wall-clock span"
        }
    }
}

data class ActiveFocusTimer(
    val sessionUid: String,
    val taskUid: String,
    val taskTitleSnapshot: String,
    val mode: FocusTimerMode,
    val plannedDurationSeconds: Long? = null,
    val state: FocusTimerState,
    val startedAt: String,
    val runningSegmentStartedAt: String?,
    val accumulatedSeconds: Long,
    val updatedAt: String,
) {
    init {
        require(sessionUid.isNotBlank()) { "Active focus session uid must not be blank" }
        require(taskUid.isNotBlank()) { "Active focus taskUid must not be blank" }
        require(taskTitleSnapshot.isNotBlank()) { "Active focus task title must not be blank" }
        require(accumulatedSeconds >= 0) { "Active focus accumulated duration must not be negative" }
        validatePlannedDuration(mode, plannedDurationSeconds)

        val started = parseInstant(startedAt, "startedAt")
        val updated = parseInstant(updatedAt, "updatedAt")
        require(updated >= started) { "Active focus updatedAt must not precede startedAt" }
        when (state) {
            FocusTimerState.RUNNING -> {
                val segmentStarted = parseInstant(
                    requireNotNull(runningSegmentStartedAt) { "Running focus timer requires a segment start" },
                    "runningSegmentStartedAt",
                )
                require(segmentStarted >= started) { "Running segment must not precede timer start" }
            }
            FocusTimerState.PAUSED -> require(runningSegmentStartedAt == null) {
                "Paused focus timer must not retain a running segment start"
            }
        }
    }
}

sealed interface FocusTimerCommand {
    data class Start(
        val taskUid: String,
        val mode: FocusTimerMode,
        val plannedDurationSeconds: Long? = null,
    ) : FocusTimerCommand

    data object Pause : FocusTimerCommand
    data object Resume : FocusTimerCommand
    data object Finish : FocusTimerCommand
    data object FinishAndCompleteTask : FocusTimerCommand
    data object Discard : FocusTimerCommand
    data object Reconcile : FocusTimerCommand
}

sealed interface FocusSessionCommand {
    data class AdjustDuration(val sessionUid: String, val durationSeconds: Long) : FocusSessionCommand
    data class Delete(val sessionUid: String) : FocusSessionCommand
}

enum class FocusTimerOutcome {
    APPLIED,
    NO_OP,
    TASK_REQUIRED,
    TASK_NOT_FOUND,
    TASK_NOT_ACTIVE,
    TIMER_ALREADY_ACTIVE,
    TIMER_NOT_FOUND,
    TIMER_REQUIRES_RESOLUTION,
    INVALID_STATE,
    INVALID_DURATION,
    CLOCK_ANOMALY,
}

enum class FocusTimerWarning {
    CLOCK_ANOMALY,
    LONG_RUNNING,
    EXCESSIVE_DURATION,
    ALARM_UNAVAILABLE,
}

data class FocusTimerSnapshot(
    val sessionUid: String,
    val taskUid: String,
    val taskTitle: String,
    val mode: FocusTimerMode,
    val state: FocusTimerState,
    val elapsedSeconds: Long,
    val remainingSeconds: Long?,
    val plannedDurationSeconds: Long?,
    val canPause: Boolean,
    val canResume: Boolean,
    val canFinish: Boolean,
    val canFinishAndComplete: Boolean,
    val warning: FocusTimerWarning? = null,
)

internal fun validatePlannedDuration(mode: FocusTimerMode, plannedDurationSeconds: Long?) {
    when (mode) {
        FocusTimerMode.STOPWATCH -> require(plannedDurationSeconds == null) {
            "Stopwatch must not have a planned duration"
        }
        FocusTimerMode.COUNTDOWN -> require(
            plannedDurationSeconds != null && plannedDurationSeconds in MIN_COUNTDOWN_SECONDS..MAX_COUNTDOWN_SECONDS,
        ) {
            "Countdown duration must be between 1 and 180 minutes"
        }
    }
}

internal fun parseInstant(value: String, field: String): Instant =
    runCatching { Instant.parse(value) }.getOrElse { throw IllegalArgumentException("Invalid focus $field", it) }

const val MIN_COUNTDOWN_SECONDS: Long = 60
const val MAX_COUNTDOWN_SECONDS: Long = 180 * 60
const val LONG_RUNNING_WARNING_SECONDS: Long = 12 * 60 * 60
const val EXCESSIVE_DURATION_SECONDS: Long = 7 * 24 * 60 * 60
const val MAX_ADJUSTED_DURATION_SECONDS: Long = 24 * 60 * 60
