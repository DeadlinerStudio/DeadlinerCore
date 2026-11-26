package com.deadliner.domain.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

class FocusTimerEngineTest {
    @Test
    fun stopwatchExcludesPausedTime() {
        val started = Instant.parse("2026-09-10T01:00:00Z")
        val active = runningTimer(started)

        val paused = assertNotNull(
            FocusTimerEngine.pause(active, Instant.parse("2026-09-10T01:10:00Z")).activeTimer,
        )
        val whilePaused = FocusTimerEngine.snapshot(paused, Instant.parse("2026-09-10T01:15:00Z"))
        assertEquals(600, whilePaused.elapsedSeconds)
        assertFalse(whilePaused.canPause)

        val resumed = assertNotNull(
            FocusTimerEngine.resume(paused, Instant.parse("2026-09-10T01:15:00Z")).activeTimer,
        )
        val completed = FocusTimerEngine.finish(
            resumed,
            Instant.parse("2026-09-10T01:35:00Z"),
            FocusCompletionKind.FOCUS_ONLY,
        )

        assertEquals(FocusTimerOutcome.APPLIED, completed.outcome)
        assertEquals(1_800, assertNotNull(completed.session).durationSeconds)
    }

    @Test
    fun countdownFinishesEarlyWithActualElapsedTime() {
        val active = runningTimer(
            started = Instant.parse("2026-09-10T02:00:00Z"),
            mode = FocusTimerMode.COUNTDOWN,
            plannedDurationSeconds = 1_500,
        )

        val completed = FocusTimerEngine.finish(
            active,
            Instant.parse("2026-09-10T02:12:00Z"),
            FocusCompletionKind.FOCUS_ONLY,
        )

        val session = assertNotNull(completed.session)
        assertEquals(720, session.durationSeconds)
        assertEquals("2026-09-10T02:12:00Z", session.endedAt)
        assertEquals(FocusCompletionKind.FOCUS_ONLY, session.completionKind)
    }

    @Test
    fun countdownReconcileCapsDurationAndUsesPlannedEnd() {
        val active = runningTimer(
            started = Instant.parse("2026-09-10T03:00:00Z"),
            mode = FocusTimerMode.COUNTDOWN,
            plannedDurationSeconds = 1_500,
        )

        val completed = FocusTimerEngine.reconcile(active, Instant.parse("2026-09-10T04:00:00Z"))

        val session = assertNotNull(completed.session)
        assertEquals(1_500, session.durationSeconds)
        assertEquals("2026-09-10T03:25:00Z", session.endedAt)
        assertEquals(FocusCompletionKind.COUNTDOWN_ELAPSED, session.completionKind)
    }

    @Test
    fun pausedCountdownDoesNotReachDeadline() {
        val active = runningTimer(
            started = Instant.parse("2026-09-10T05:00:00Z"),
            mode = FocusTimerMode.COUNTDOWN,
            plannedDurationSeconds = 1_500,
        )
        val paused = assertNotNull(
            FocusTimerEngine.pause(active, Instant.parse("2026-09-10T05:10:00Z")).activeTimer,
        )

        val reconciled = FocusTimerEngine.reconcile(paused, Instant.parse("2026-09-11T05:00:00Z"))

        assertEquals(FocusTimerOutcome.NO_OP, reconciled.outcome)
        assertNull(reconciled.session)
        assertEquals(600, FocusTimerEngine.snapshot(paused, Instant.parse("2026-09-11T05:00:00Z")).elapsedSeconds)
    }

    @Test
    fun clockRollbackIsReportedWithoutAddingTime() {
        val active = runningTimer(Instant.parse("2026-09-10T06:00:00Z"))

        val snapshot = FocusTimerEngine.snapshot(active, Instant.parse("2026-09-10T05:59:59Z"))
        val completed = FocusTimerEngine.finish(
            active,
            Instant.parse("2026-09-10T05:59:59Z"),
            FocusCompletionKind.FOCUS_ONLY,
        )

        assertEquals(0, snapshot.elapsedSeconds)
        assertEquals(FocusTimerWarning.CLOCK_ANOMALY, snapshot.warning)
        assertEquals(FocusTimerOutcome.CLOCK_ANOMALY, completed.outcome)
        assertNull(completed.session)
    }

    @Test
    fun snapshotOnlyAllowsFinishAndCompleteForActiveTask() {
        val active = runningTimer(Instant.parse("2026-09-10T07:00:00Z"))
        val now = Instant.parse("2026-09-10T07:01:00Z")

        assertTrue(FocusTimerEngine.snapshot(active, now, taskIsActive = true).canFinishAndComplete)
        assertFalse(FocusTimerEngine.snapshot(active, now, taskIsActive = false).canFinishAndComplete)
    }

    private fun runningTimer(
        started: Instant,
        mode: FocusTimerMode = FocusTimerMode.STOPWATCH,
        plannedDurationSeconds: Long? = null,
    ) = ActiveFocusTimer(
        sessionUid = "focus.session",
        taskUid = "task.focus",
        taskTitleSnapshot = "Write specification",
        mode = mode,
        plannedDurationSeconds = plannedDurationSeconds,
        state = FocusTimerState.RUNNING,
        startedAt = started.toString(),
        runningSegmentStartedAt = started.toString(),
        accumulatedSeconds = 0,
        updatedAt = started.toString(),
    )
}
