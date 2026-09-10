package com.deadliner.service.focus

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.TaskRepository
import com.deadliner.db.repository.focus.ActiveFocusTimerStore
import com.deadliner.db.repository.focus.FocusSessionRepository
import com.deadliner.domain.focus.ActiveFocusTimer
import com.deadliner.domain.focus.FocusCompletionKind
import com.deadliner.domain.focus.FocusSession
import com.deadliner.domain.focus.FocusTimerCommand
import com.deadliner.domain.focus.FocusTimerEngine
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.focus.FocusTimerOutcome
import com.deadliner.domain.focus.FocusTimerSnapshot
import com.deadliner.domain.focus.FocusTimerState
import com.deadliner.domain.focus.FocusTimerWarning
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.task.TaskActionOutcome
import com.deadliner.domain.task.TaskState
import com.deadliner.model.ChangeOperation
import com.deadliner.platform.FocusAlarmPort
import com.deadliner.platform.PlatformIdentity
import com.deadliner.sync.RepositoryMutationNotifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class FocusTimerService(
    private val database: AppDatabase,
    private val tasks: TaskRepository,
    private val sessions: FocusSessionRepository,
    private val activeTimers: ActiveFocusTimerStore,
    private val alarmPort: FocusAlarmPort = FocusAlarmPort.NONE,
    private val mutationNotifier: RepositoryMutationNotifier = RepositoryMutationNotifier.NONE,
) {
    private val commandMutex = Mutex()

    suspend fun execute(
        command: FocusTimerCommand,
        now: Instant = Clock.System.now(),
    ): FocusTimerResult = commandMutex.withLock {
        when (command) {
            is FocusTimerCommand.Start -> start(command, now)
            FocusTimerCommand.Pause -> withTerminalReconciliation(now) { active -> pause(active, now) }
            FocusTimerCommand.Resume -> withTerminalReconciliation(now) { active -> resume(active, now) }
            FocusTimerCommand.Finish -> withTerminalReconciliation(now) { active ->
                finish(active, now, FocusCompletionKind.FOCUS_ONLY, completeTask = false)
            }
            FocusTimerCommand.FinishAndCompleteTask -> withTerminalReconciliation(now) { active ->
                finish(active, now, FocusCompletionKind.FOCUS_AND_TASK_COMPLETED, completeTask = true)
            }
            FocusTimerCommand.Discard -> withTerminalReconciliation(now) { active -> discard(active) }
            FocusTimerCommand.Reconcile -> reconcile(now)
        }
    }

    suspend fun current(now: Instant = Clock.System.now()): FocusTimerResult = commandMutex.withLock {
        val active = activeTimers.current()
            ?: return@withLock FocusTimerResult(FocusTimerOutcome.TIMER_NOT_FOUND)
        resultForActive(FocusTimerOutcome.NO_OP, active, now)
    }

    private fun start(command: FocusTimerCommand.Start, now: Instant): FocusTimerResult {
        activeTimers.current()?.let { active ->
            return resultForActive(FocusTimerOutcome.TIMER_ALREADY_ACTIVE, active, now)
        }
        if (command.taskUid.isBlank()) return FocusTimerResult(FocusTimerOutcome.TASK_REQUIRED)
        val task = tasks.find(command.taskUid) ?: return FocusTimerResult(FocusTimerOutcome.TASK_NOT_FOUND)
        if (!task.canFocus()) return FocusTimerResult(FocusTimerOutcome.TASK_NOT_ACTIVE, task = task)

        val active = runCatching {
            ActiveFocusTimer(
                sessionUid = PlatformIdentity.newId(),
                taskUid = task.uid,
                taskTitleSnapshot = task.title.ifBlank { task.uid },
                mode = command.mode,
                plannedDurationSeconds = command.plannedDurationSeconds,
                state = FocusTimerState.RUNNING,
                startedAt = now.toString(),
                runningSegmentStartedAt = now.toString(),
                accumulatedSeconds = 0,
                updatedAt = now.toString(),
            )
        }.getOrElse {
            return FocusTimerResult(FocusTimerOutcome.INVALID_DURATION, task = task)
        }
        activeTimers.save(active)
        val alarmWarning = scheduleAlarm(active)
        return resultForActive(FocusTimerOutcome.APPLIED, active, now, task, alarmWarning)
    }

    private fun pause(active: ActiveFocusTimer, now: Instant): FocusTimerResult {
        val transition = FocusTimerEngine.pause(active, now)
        val updated = transition.activeTimer ?: active
        if (transition.outcome == FocusTimerOutcome.APPLIED) {
            activeTimers.save(updated)
            alarmPort.cancelCountdown(active.sessionUid)
        }
        return resultForActive(transition.outcome, updated, now)
    }

    private fun resume(active: ActiveFocusTimer, now: Instant): FocusTimerResult {
        val task = tasks.find(active.taskUid) ?: return FocusTimerResult(
            FocusTimerOutcome.TASK_NOT_FOUND,
            activeTimer = active,
        )
        if (!task.canFocus()) {
            return FocusTimerResult(
                FocusTimerOutcome.TASK_NOT_ACTIVE,
                activeTimer = active,
                snapshot = FocusTimerEngine.snapshot(active, now, task.title, taskIsActive = false),
                task = task,
            )
        }
        val transition = FocusTimerEngine.resume(active, now)
        val updated = transition.activeTimer ?: active
        val alarmWarning = if (transition.outcome == FocusTimerOutcome.APPLIED) {
            activeTimers.save(updated)
            scheduleAlarm(updated)
        } else {
            null
        }
        return resultForActive(transition.outcome, updated, now, task, alarmWarning)
    }

    private fun finish(
        active: ActiveFocusTimer,
        now: Instant,
        completionKind: FocusCompletionKind,
        completeTask: Boolean,
    ): FocusTimerResult {
        val task = tasks.find(active.taskUid)
        if (completeTask && (task == null || !task.canFocus())) {
            return FocusTimerResult(
                outcome = if (task == null) FocusTimerOutcome.TASK_NOT_FOUND else FocusTimerOutcome.TASK_NOT_ACTIVE,
                activeTimer = active,
                snapshot = FocusTimerEngine.snapshot(
                    active,
                    now,
                    task?.title ?: active.taskTitleSnapshot,
                    taskIsActive = false,
                ),
                task = task,
            )
        }

        val completed = FocusTimerEngine.finish(active, now, completionKind)
        val session = completed.session
            ?: return resultForActive(completed.outcome, active, now, task)
        var completedTask: Task? = task
        database.transaction {
            sessions.saveInTransaction(session, ChangeOperation.INSERT)
            activeTimers.clear()
            if (completeTask) {
                val taskResult = tasks.applyActionInTransaction(active.taskUid, TaskAction.MARK_COMPLETE, now.toString())
                check(taskResult.outcome == TaskActionOutcome.APPLIED) {
                    "Focus finish-and-complete could not transition task ${active.taskUid}"
                }
                completedTask = taskResult.task
            }
        }
        mutationNotifier.notifyMutation()
        val alarmWarning = cancelAlarm(active.sessionUid)
        return FocusTimerResult(
            outcome = FocusTimerOutcome.APPLIED,
            session = session,
            task = completedTask,
            warning = alarmWarning,
        )
    }

    private fun discard(active: ActiveFocusTimer): FocusTimerResult {
        activeTimers.clear()
        val warning = cancelAlarm(active.sessionUid)
        return FocusTimerResult(FocusTimerOutcome.APPLIED, warning = warning)
    }

    private fun reconcile(now: Instant): FocusTimerResult {
        val active = activeTimers.current() ?: return FocusTimerResult(FocusTimerOutcome.NO_OP)
        val task = tasks.find(active.taskUid)
        if (task == null || !task.canFocus()) {
            val completed = FocusTimerEngine.finish(active, now, FocusCompletionKind.TASK_BECAME_INACTIVE)
            if (completed.outcome == FocusTimerOutcome.INVALID_DURATION) {
                activeTimers.clear()
                return FocusTimerResult(
                    FocusTimerOutcome.APPLIED,
                    task = task,
                    warning = cancelAlarm(active.sessionUid),
                )
            }
            val session = completed.session
                ?: return resultForActive(completed.outcome, active, now, task)
            persistReconciledSession(session)
            return FocusTimerResult(
                FocusTimerOutcome.APPLIED,
                session = session,
                task = task,
                warning = cancelAlarm(active.sessionUid),
            )
        }

        val reconciled = FocusTimerEngine.reconcile(active, now)
        val session = reconciled.session
        if (session != null) {
            persistReconciledSession(session)
            return FocusTimerResult(
                FocusTimerOutcome.APPLIED,
                session = session,
                task = task,
                warning = cancelAlarm(active.sessionUid),
            )
        }
        return resultForActive(reconciled.outcome, active, now, task, reconcileAlarm(active))
    }

    private fun persistReconciledSession(session: FocusSession) {
        database.transaction {
            sessions.saveInTransaction(session, ChangeOperation.INSERT)
            activeTimers.clear()
        }
        mutationNotifier.notifyMutation()
    }

    private fun withTerminalReconciliation(
        now: Instant,
        operation: (ActiveFocusTimer) -> FocusTimerResult,
    ): FocusTimerResult {
        val active = activeTimers.current() ?: return FocusTimerResult(FocusTimerOutcome.TIMER_NOT_FOUND)
        val task = tasks.find(active.taskUid)
        val calculation = FocusTimerEngine.calculate(active, now)
        if (task == null || !task.canFocus() || calculation.countdownElapsed) return reconcile(now)
        return operation(active)
    }

    private fun resultForActive(
        outcome: FocusTimerOutcome,
        active: ActiveFocusTimer,
        now: Instant,
        resolvedTask: Task? = tasks.find(active.taskUid),
        warning: FocusTimerWarning? = null,
    ): FocusTimerResult {
        val taskIsActive = resolvedTask?.canFocus() == true
        val snapshot = FocusTimerEngine.snapshot(
            active,
            now,
            resolvedTask?.title ?: active.taskTitleSnapshot,
            taskIsActive,
        )
        return FocusTimerResult(
            outcome = outcome,
            activeTimer = active,
            snapshot = if (warning == null) snapshot else snapshot.copy(warning = warning),
            task = resolvedTask,
            warning = warning,
        )
    }

    private fun scheduleAlarm(active: ActiveFocusTimer): FocusTimerWarning? {
        if (active.mode != FocusTimerMode.COUNTDOWN) return null
        val fireAt = FocusTimerEngine.countdownAlarmAt(active) ?: return null
        return alarmPort.scheduleCountdown(
            active.sessionUid,
            fireAt.toString(),
            active.taskTitleSnapshot,
        ).warningIfFailed()
    }

    private fun cancelAlarm(sessionUid: String): FocusTimerWarning? =
        alarmPort.cancelCountdown(sessionUid).warningIfFailed()

    private fun reconcileAlarm(active: ActiveFocusTimer): FocusTimerWarning? = when {
        active.mode != FocusTimerMode.COUNTDOWN -> null
        active.state == FocusTimerState.RUNNING -> scheduleAlarm(active)
        else -> cancelAlarm(active.sessionUid)
    }

    private fun com.deadliner.platform.FocusAlarmResult.warningIfFailed(): FocusTimerWarning? =
        if (succeeded) null else FocusTimerWarning.ALARM_UNAVAILABLE

    private fun Task.canFocus(): Boolean = !isDeleted && state == TaskState.ACTIVE
}

data class FocusTimerResult(
    val outcome: FocusTimerOutcome,
    val activeTimer: ActiveFocusTimer? = null,
    val snapshot: FocusTimerSnapshot? = null,
    val session: FocusSession? = null,
    val task: Task? = null,
    val warning: FocusTimerWarning? = null,
)
