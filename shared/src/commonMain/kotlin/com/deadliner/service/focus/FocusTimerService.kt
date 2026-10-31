package com.deadliner.service.focus

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.TaskRepository
import com.deadliner.db.repository.HabitRepository
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.focus.FocusTargetKind
import com.deadliner.db.repository.focus.ActiveFocusTimerStore
import com.deadliner.db.repository.focus.FocusSessionRepository
import com.deadliner.domain.focus.ActiveFocusTimer
import com.deadliner.domain.focus.FocusCompletionKind
import com.deadliner.domain.focus.FocusSession
import com.deadliner.domain.focus.FocusTaskPolicy
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
    habits: HabitRepository? = null,
) {
    private val commandMutex = Mutex()
    private val habitOperations = habits?.let { HabitFocusOperations(database, it, sessions, activeTimers, mutationNotifier) }

    suspend fun execute(
        command: FocusTimerCommand,
        now: Instant = Clock.System.now(),
        expectedSessionUid: String? = null,
        expectedUpdatedAt: String? = null,
    ): FocusTimerResult = commandMutex.withLock {
        if (expectedSessionUid != null || expectedUpdatedAt != null) {
            val active = activeTimers.current()
            if (active == null || active.sessionUid != expectedSessionUid || active.updatedAt != expectedUpdatedAt) {
                return@withLock active?.let { resultForActive(FocusTimerOutcome.INVALID_STATE, it, now) }
                    ?: FocusTimerResult(FocusTimerOutcome.INVALID_STATE)
            }
        }
        when (command) {
            is FocusTimerCommand.Start -> start(command, now)
            is FocusTimerCommand.StartHabit -> {
                val result = habitOperations?.start(command, now) ?: FocusTimerResult(FocusTimerOutcome.HABIT_NOT_FOUND)
                result.activeTimer?.let { resultForActive(result.outcome, it, now,
                    warning = if (result.outcome == FocusTimerOutcome.APPLIED) scheduleAlarm(it) else null) } ?: result
            }
            is FocusTimerCommand.FinishAndRecordHabit -> {
                val result = habitOperations?.finishAndRecord(command, now) ?: FocusTimerResult(FocusTimerOutcome.HABIT_NOT_FOUND)
                val warning = if (result.outcome == FocusTimerOutcome.APPLIED) cancelAlarm(command.sessionUid) else null
                result.activeTimer?.let { resultForActive(result.outcome, it, now, warning = warning)
                    .copy(session = result.session) } ?: result.copy(warning = warning)
            }
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

    fun habitRecordContext(session: FocusSession, now: Instant = Clock.System.now()) =
        session.takeIf { it.boundTarget.kind == FocusTargetKind.HABIT && it.habitRecordedAmount == null && !it.isDeleted }
            ?.let { saved -> habitOperations?.contextForSession(saved, now) }

    suspend fun resolveHabitChange(uid: String, delete: Boolean, now: Instant = Clock.System.now(), expectedSessionUid: String? = null): FocusTimerResult = commandMutex.withLock {
        val confirmedActive = activeTimers.current()
        if (expectedSessionUid != null && confirmedActive?.sessionUid != expectedSessionUid) {
            return@withLock confirmedActive?.let { resultForActive(FocusTimerOutcome.INVALID_STATE, it, now) }
                ?: FocusTimerResult(FocusTimerOutcome.INVALID_STATE)
        }
        val sessionUid = activeTimers.current()?.sessionUid
        val result = habitOperations?.resolveChange(uid, delete, now) ?: FocusTimerResult(FocusTimerOutcome.HABIT_NOT_FOUND)
        if (result.outcome == FocusTimerOutcome.APPLIED) {
            mutationNotifier.notifyMutation()
            result.copy(warning = sessionUid?.let(::cancelAlarm))
        } else result
    }

    suspend fun resolveTaskAction(
        taskUid: String,
        action: TaskAction,
        now: Instant = Clock.System.now(),
        expectedSessionUid: String? = null,
    ): FocusTimerResult = commandMutex.withLock {
        val active = activeTimers.current() ?: return@withLock FocusTimerResult(FocusTimerOutcome.TIMER_NOT_FOUND)
        if (expectedSessionUid != null && active.sessionUid != expectedSessionUid) return@withLock resultForActive(FocusTimerOutcome.INVALID_STATE, active, now)
        val task = tasks.find(taskUid) ?: return@withLock FocusTimerResult(FocusTimerOutcome.TASK_NOT_FOUND)
        if (active.taskUid != taskUid || !FocusTaskPolicy.requiresResolution(task, action, active)) {
            return@withLock resultForActive(FocusTimerOutcome.INVALID_STATE, active, now, task)
        }
        finishAndApplyTaskAction(active, now, action, FocusCompletionKind.FOCUS_AND_TASK_CHANGED)
    }

    suspend fun resolveTaskDeletion(
        taskUid: String,
        now: Instant = Clock.System.now(),
        expectedSessionUid: String? = null,
    ): FocusTimerResult = commandMutex.withLock {
        val active = activeTimers.current() ?: return@withLock FocusTimerResult(FocusTimerOutcome.TIMER_NOT_FOUND)
        if (expectedSessionUid != null && active.sessionUid != expectedSessionUid) return@withLock resultForActive(FocusTimerOutcome.INVALID_STATE, active, now)
        val task = tasks.find(taskUid) ?: return@withLock FocusTimerResult(FocusTimerOutcome.TASK_NOT_FOUND)
        if (!FocusTaskPolicy.requiresResolutionForDelete(taskUid, active)) {
            return@withLock resultForActive(FocusTimerOutcome.INVALID_STATE, active, now, task)
        }
        finishAndDeleteTask(active, task, now)
    }

    /** Completes only the saved session's Task. Never seals another/new timer or inserts a session. */
    suspend fun completeSavedTask(sessionUid: String, now: Instant = Clock.System.now()): FocusTimerResult = commandMutex.withLock {
        val active = activeTimers.current()
        fun failure(outcome: FocusTimerOutcome, session: FocusSession? = null): FocusTimerResult =
            active?.let { resultForActive(outcome, it, now).copy(session = session) } ?: FocusTimerResult(outcome, session = session)
        val session = sessions.find(sessionUid)?.takeUnless { it.isDeleted }
            ?: return@withLock failure(FocusTimerOutcome.TIMER_NOT_FOUND)
        if (session.boundTarget.kind != FocusTargetKind.TASK) return@withLock failure(FocusTimerOutcome.INVALID_STATE)
        if (active?.boundTarget == session.boundTarget) return@withLock resultForActive(FocusTimerOutcome.TIMER_REQUIRES_RESOLUTION, active, now)
        val task = tasks.find(session.boundTarget.uid)?.takeUnless { it.isDeleted }
            ?: return@withLock failure(FocusTimerOutcome.TASK_NOT_FOUND, session)
        if (task.state == TaskState.COMPLETED || task.state == TaskState.ARCHIVED) {
            return@withLock FocusTimerResult(FocusTimerOutcome.NO_OP, session = session, task = task, activeTimer = active,
                snapshot = active?.let { resultForActive(FocusTimerOutcome.NO_OP, it, now).snapshot })
        }
        if (!task.canFocus()) return@withLock FocusTimerResult(FocusTimerOutcome.TASK_NOT_ACTIVE, session = session, activeTimer = active,
            snapshot = active?.let { resultForActive(FocusTimerOutcome.NO_OP, it, now).snapshot })
        val result = tasks.applyAction(task.uid, TaskAction.MARK_COMPLETE, now.toString())
        return@withLock FocusTimerResult(if (result.outcome == TaskActionOutcome.APPLIED) FocusTimerOutcome.APPLIED else FocusTimerOutcome.INVALID_STATE,
            session = session, task = result.task, activeTimer = active, snapshot = active?.let { resultForActive(FocusTimerOutcome.NO_OP, it, now).snapshot })
    }

    private fun start(command: FocusTimerCommand.Start, now: Instant): FocusTimerResult {
        var outcome = FocusTimerOutcome.INVALID_STATE
        var resolvedTask: Task? = null
        var resolvedActive: ActiveFocusTimer? = null
        database.transaction {
            val existingActive = activeTimers.current()
            if (existingActive != null) {
                outcome = FocusTimerOutcome.TIMER_ALREADY_ACTIVE
                resolvedActive = existingActive
                return@transaction
            }
            if (command.taskUid.isBlank()) {
                outcome = FocusTimerOutcome.TASK_REQUIRED
                return@transaction
            }
            val task = tasks.find(command.taskUid)
            if (task == null) {
                outcome = FocusTimerOutcome.TASK_NOT_FOUND
                return@transaction
            }
            resolvedTask = task
            if (!task.canFocus()) {
                outcome = FocusTimerOutcome.TASK_NOT_ACTIVE
                return@transaction
            }
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
            }.getOrNull()
            if (active == null) {
                outcome = FocusTimerOutcome.INVALID_DURATION
                return@transaction
            }
            activeTimers.save(active)
            resolvedActive = active
            outcome = FocusTimerOutcome.APPLIED
        }
        val active = resolvedActive
        return when {
            active != null && outcome == FocusTimerOutcome.APPLIED ->
                resultForActive(outcome, active, now, resolvedTask, scheduleAlarm(active))
            active != null -> resultForActive(outcome, active, now)
            else -> FocusTimerResult(outcome, task = resolvedTask)
        }
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
        if (active.boundTarget.kind == FocusTargetKind.HABIT) {
            val transition = FocusTimerEngine.resume(active, now)
            val updated = transition.activeTimer ?: active
            val warning = if (transition.outcome == FocusTimerOutcome.APPLIED) {
                activeTimers.save(updated)
                scheduleAlarm(updated)
            } else null
            return resultForActive(transition.outcome, updated, now, warning = warning)
        }
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
        if (completeTask) {
            if (active.boundTarget.kind != FocusTargetKind.TASK) return resultForActive(FocusTimerOutcome.INVALID_STATE, active, now)
            return finishAndApplyTaskAction(
                active,
                now,
                TaskAction.MARK_COMPLETE,
                FocusCompletionKind.FOCUS_AND_TASK_COMPLETED,
            )
        }
        val task = tasks.find(active.taskUid)
        val completed = FocusTimerEngine.finish(active, now, completionKind)
        val session = completed.session
            ?: return resultForActive(completed.outcome, active, now, task)
        database.transaction {
            sessions.saveInTransaction(session, ChangeOperation.INSERT)
            activeTimers.clear()
        }
        mutationNotifier.notifyMutation()
        val alarmWarning = cancelAlarm(active.sessionUid)
        return FocusTimerResult(
            outcome = FocusTimerOutcome.APPLIED,
            session = session,
            task = task,
            warning = alarmWarning,
            habit = habitOperations?.habit(active),
        )
    }

    private fun finishAndApplyTaskAction(
        active: ActiveFocusTimer,
        now: Instant,
        action: TaskAction,
        completionKind: FocusCompletionKind,
    ): FocusTimerResult {
        val task = tasks.find(active.taskUid)
        if (task == null || !task.canFocus()) {
            return inactiveTaskResult(active, task, now)
        }
        val completed = FocusTimerEngine.finish(active, now, completionKind)
        val session = completed.session
        if (session == null && completed.outcome != FocusTimerOutcome.INVALID_DURATION) return resultForActive(completed.outcome, active, now, task)
        var updatedTask: Task? = task
        database.transaction {
            session?.let { sessions.saveInTransaction(it, ChangeOperation.INSERT) }
            activeTimers.clear()
            val taskResult = tasks.applyActionInTransaction(active.taskUid, action, now.toString())
            check(taskResult.outcome == TaskActionOutcome.APPLIED) {
                "Focus resolution could not apply $action to task ${active.taskUid}"
            }
            updatedTask = taskResult.task
        }
        mutationNotifier.notifyMutation()
        return FocusTimerResult(
            outcome = FocusTimerOutcome.APPLIED,
            session = session,
            task = updatedTask,
            warning = cancelAlarm(active.sessionUid),
        )
    }

    private fun finishAndDeleteTask(
        active: ActiveFocusTimer,
        task: Task,
        now: Instant,
    ): FocusTimerResult {
        val completed = FocusTimerEngine.finish(active, now, FocusCompletionKind.FOCUS_AND_TASK_CHANGED)
        val session = completed.session
        if (session == null && completed.outcome != FocusTimerOutcome.INVALID_DURATION) return resultForActive(completed.outcome, active, now, task)
        database.transaction {
            session?.let { sessions.saveInTransaction(it, ChangeOperation.INSERT) }
            activeTimers.clear()
            tasks.deleteInTransaction(task.uid, now.toString())
        }
        mutationNotifier.notifyMutation()
        return FocusTimerResult(
            outcome = FocusTimerOutcome.APPLIED,
            session = session,
            task = task.copy(updatedAt = now.toString(), isDeleted = true),
            warning = cancelAlarm(active.sessionUid),
        )
    }

    private fun inactiveTaskResult(active: ActiveFocusTimer, task: Task?, now: Instant) = FocusTimerResult(
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

    private fun discard(active: ActiveFocusTimer): FocusTimerResult {
        activeTimers.clear()
        val warning = cancelAlarm(active.sessionUid)
        return FocusTimerResult(FocusTimerOutcome.APPLIED, warning = warning)
    }

    private fun reconcile(now: Instant): FocusTimerResult {
        val active = activeTimers.current() ?: return FocusTimerResult(FocusTimerOutcome.NO_OP)
        if (active.boundTarget.kind == FocusTargetKind.HABIT) {
            val result = habitOperations?.reconcile(active, now) ?: return FocusTimerResult(FocusTimerOutcome.HABIT_NOT_FOUND)
            val warning = if (result.activeTimer == null) cancelAlarm(active.sessionUid) else reconcileAlarm(active)
            return result.copy(warning = warning, snapshot = result.snapshot?.copy(warning = warning))
        }
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
        val targetIsActive = if (active.boundTarget.kind == FocusTargetKind.HABIT) habitOperations?.isActive(active) == true else task?.canFocus() == true
        if (!targetIsActive || calculation.countdownElapsed) return reconcile(now)
        return operation(active)
    }

    private fun resultForActive(
        outcome: FocusTimerOutcome,
        active: ActiveFocusTimer,
        now: Instant,
        resolvedTask: Task? = tasks.find(active.taskUid),
        warning: FocusTimerWarning? = null,
    ): FocusTimerResult {
        if (active.boundTarget.kind == FocusTargetKind.HABIT) {
            val result = habitOperations?.snapshot(outcome, active, now) ?: return FocusTimerResult(FocusTimerOutcome.HABIT_NOT_FOUND)
            return result.copy(warning = warning, snapshot = result.snapshot?.copy(warning = warning))
        }
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
    val habit: Habit? = null,
)
