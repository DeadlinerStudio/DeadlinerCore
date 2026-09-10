package com.deadliner.domain.focus

import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

data class FocusTimerCalculation(
    val elapsedSeconds: Long,
    val remainingSeconds: Long?,
    val countdownElapsed: Boolean,
    val clockAnomaly: Boolean,
)

data class FocusTimerTransition(
    val outcome: FocusTimerOutcome,
    val activeTimer: ActiveFocusTimer? = null,
)

data class FocusTimerCompletion(
    val outcome: FocusTimerOutcome,
    val session: FocusSession? = null,
)

object FocusTimerEngine {
    fun snapshot(
        active: ActiveFocusTimer,
        now: Instant,
        taskTitle: String = active.taskTitleSnapshot,
        taskIsActive: Boolean = true,
    ): FocusTimerSnapshot {
        val calculation = calculate(active, now)
        val warning = when {
            calculation.clockAnomaly -> FocusTimerWarning.CLOCK_ANOMALY
            calculation.elapsedSeconds >= EXCESSIVE_DURATION_SECONDS -> FocusTimerWarning.EXCESSIVE_DURATION
            calculation.elapsedSeconds >= LONG_RUNNING_WARNING_SECONDS -> FocusTimerWarning.LONG_RUNNING
            else -> null
        }
        val hasDuration = calculation.elapsedSeconds > 0
        return FocusTimerSnapshot(
            sessionUid = active.sessionUid,
            taskUid = active.taskUid,
            taskTitle = taskTitle,
            mode = active.mode,
            state = active.state,
            elapsedSeconds = calculation.elapsedSeconds,
            remainingSeconds = calculation.remainingSeconds,
            plannedDurationSeconds = active.plannedDurationSeconds,
            canPause = active.state == FocusTimerState.RUNNING,
            canResume = active.state == FocusTimerState.PAUSED && taskIsActive,
            canFinish = hasDuration && !calculation.clockAnomaly,
            canFinishAndComplete = hasDuration && !calculation.clockAnomaly && taskIsActive,
            warning = warning,
        )
    }

    fun calculate(active: ActiveFocusTimer, now: Instant): FocusTimerCalculation {
        val liveSeconds = if (active.state == FocusTimerState.RUNNING) {
            val segmentStarted = parseInstant(requireNotNull(active.runningSegmentStartedAt), "runningSegmentStartedAt")
            val delta = (now - segmentStarted).inWholeSeconds
            if (delta < 0) {
                return FocusTimerCalculation(
                    elapsedSeconds = active.accumulatedSeconds,
                    remainingSeconds = remaining(active, active.accumulatedSeconds),
                    countdownElapsed = false,
                    clockAnomaly = true,
                )
            }
            delta
        } else {
            0
        }
        val rawElapsed = active.accumulatedSeconds + liveSeconds
        val displayedElapsed = active.plannedDurationSeconds?.let { minOf(rawElapsed, it) } ?: rawElapsed
        return FocusTimerCalculation(
            elapsedSeconds = displayedElapsed,
            remainingSeconds = remaining(active, rawElapsed),
            countdownElapsed = active.mode == FocusTimerMode.COUNTDOWN &&
                rawElapsed >= requireNotNull(active.plannedDurationSeconds),
            clockAnomaly = false,
        )
    }

    fun pause(active: ActiveFocusTimer, now: Instant): FocusTimerTransition {
        if (active.state == FocusTimerState.PAUSED) {
            return FocusTimerTransition(FocusTimerOutcome.NO_OP, active)
        }
        val calculation = calculate(active, now)
        if (calculation.clockAnomaly) return FocusTimerTransition(FocusTimerOutcome.CLOCK_ANOMALY, active)
        if (calculation.countdownElapsed) return FocusTimerTransition(FocusTimerOutcome.INVALID_STATE, active)
        return FocusTimerTransition(
            FocusTimerOutcome.APPLIED,
            active.copy(
                state = FocusTimerState.PAUSED,
                runningSegmentStartedAt = null,
                accumulatedSeconds = calculation.elapsedSeconds,
                updatedAt = now.toString(),
            ),
        )
    }

    fun resume(active: ActiveFocusTimer, now: Instant): FocusTimerTransition {
        if (active.state == FocusTimerState.RUNNING) {
            return FocusTimerTransition(FocusTimerOutcome.NO_OP, active)
        }
        if (active.mode == FocusTimerMode.COUNTDOWN &&
            active.accumulatedSeconds >= requireNotNull(active.plannedDurationSeconds)
        ) {
            return FocusTimerTransition(FocusTimerOutcome.INVALID_STATE, active)
        }
        return FocusTimerTransition(
            FocusTimerOutcome.APPLIED,
            active.copy(
                state = FocusTimerState.RUNNING,
                runningSegmentStartedAt = now.toString(),
                updatedAt = now.toString(),
            ),
        )
    }

    fun finish(
        active: ActiveFocusTimer,
        now: Instant,
        completionKind: FocusCompletionKind,
    ): FocusTimerCompletion {
        val calculation = calculate(active, now)
        if (calculation.clockAnomaly) return FocusTimerCompletion(FocusTimerOutcome.CLOCK_ANOMALY)
        if (calculation.elapsedSeconds <= 0) return FocusTimerCompletion(FocusTimerOutcome.INVALID_DURATION)
        val endedAt = if (completionKind == FocusCompletionKind.COUNTDOWN_ELAPSED) {
            countdownEnd(active)
        } else {
            now
        }
        return FocusTimerCompletion(
            outcome = FocusTimerOutcome.APPLIED,
            session = FocusSession(
                uid = active.sessionUid,
                taskUid = active.taskUid,
                taskTitleSnapshot = active.taskTitleSnapshot,
                mode = active.mode,
                plannedDurationSeconds = active.plannedDurationSeconds,
                startedAt = active.startedAt,
                endedAt = endedAt.toString(),
                durationSeconds = calculation.elapsedSeconds,
                completionKind = completionKind,
                createdAt = endedAt.toString(),
                updatedAt = endedAt.toString(),
            ),
        )
    }

    fun reconcile(active: ActiveFocusTimer, now: Instant): FocusTimerCompletion {
        val calculation = calculate(active, now)
        return when {
            calculation.clockAnomaly -> FocusTimerCompletion(FocusTimerOutcome.CLOCK_ANOMALY)
            calculation.countdownElapsed -> finish(active, now, FocusCompletionKind.COUNTDOWN_ELAPSED)
            else -> FocusTimerCompletion(FocusTimerOutcome.NO_OP)
        }
    }

    fun countdownAlarmAt(active: ActiveFocusTimer): Instant? {
        if (active.mode != FocusTimerMode.COUNTDOWN || active.state != FocusTimerState.RUNNING) return null
        val segmentStarted = parseInstant(requireNotNull(active.runningSegmentStartedAt), "runningSegmentStartedAt")
        val remaining = requireNotNull(active.plannedDurationSeconds) - active.accumulatedSeconds
        return segmentStarted + remaining.coerceAtLeast(0).seconds
    }

    private fun remaining(active: ActiveFocusTimer, elapsedSeconds: Long): Long? =
        active.plannedDurationSeconds?.let { (it - elapsedSeconds).coerceAtLeast(0) }

    private fun countdownEnd(active: ActiveFocusTimer): Instant {
        val segmentStarted = parseInstant(requireNotNull(active.runningSegmentStartedAt), "runningSegmentStartedAt")
        val remainingAtSegmentStart = requireNotNull(active.plannedDurationSeconds) - active.accumulatedSeconds
        return segmentStarted + remainingAtSegmentStart.coerceAtLeast(0).seconds
    }
}
