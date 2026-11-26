package com.deadliner.service.focus

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.HabitRepository
import com.deadliner.db.repository.focus.ActiveFocusTimerStore
import com.deadliner.db.repository.focus.FocusSessionRepository
import com.deadliner.domain.focus.*
import com.deadliner.domain.habit.*
import com.deadliner.model.ChangeOperation
import com.deadliner.platform.PlatformIdentity
import com.deadliner.sync.RepositoryMutationNotifier
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Called under the timer service command mutex; every joint mutation is also a SQL transaction. */
internal class HabitFocusOperations(
    private val database: AppDatabase,
    private val habits: HabitRepository,
    private val sessions: FocusSessionRepository,
    private val activeTimers: ActiveFocusTimerStore,
    private val mutationNotifier: RepositoryMutationNotifier,
) {
    fun habit(active: ActiveFocusTimer): Habit? = active.target?.uid?.let(habits::find)
    fun isActive(active: ActiveFocusTimer): Boolean = HabitFocusPolicy.canFocus(habit(active))
    fun contextForSession(session: FocusSession, now: Instant): FocusHabitRecordContext? =
        habits.find(session.boundTarget.uid)?.let { context(it, session.habitOccurredOn, now) }

    fun resolveChange(uid: String, delete: Boolean, now: Instant): FocusTimerResult = database.transactionWithResult {
        val active = activeTimers.current()?.takeIf { it.boundTarget == FocusTargetRef(FocusTargetKind.HABIT, uid) }
            ?: return@transactionWithResult FocusTimerResult(FocusTimerOutcome.TIMER_NOT_FOUND)
        val habit = habits.find(uid) ?: return@transactionWithResult FocusTimerResult(FocusTimerOutcome.HABIT_NOT_FOUND)
        val completion = FocusTimerEngine.finish(active, now, FocusCompletionKind.HABIT_BECAME_INACTIVE)
        completion.session?.let { sessions.saveInTransaction(it, ChangeOperation.INSERT) }
        if (completion.session == null && completion.outcome != FocusTimerOutcome.INVALID_DURATION) {
            return@transactionWithResult snapshot(completion.outcome, active, now)
        }
        activeTimers.clear()
        if (delete) habits.deleteInTransaction(uid, now.toString()) else habits.applyStatusActionInTransaction(uid, HabitStatusAction.ARCHIVE, now.toString())
        FocusTimerResult(FocusTimerOutcome.APPLIED, session = completion.session, habit = habit)
    }

    fun start(command: FocusTimerCommand.StartHabit, now: Instant): FocusTimerResult = database.transactionWithResult {
        activeTimers.current()?.let { return@transactionWithResult FocusTimerResult(FocusTimerOutcome.TIMER_ALREADY_ACTIVE, activeTimer = it) }
        val habit = habits.find(command.habitUid) ?: return@transactionWithResult FocusTimerResult(FocusTimerOutcome.HABIT_NOT_FOUND)
        if (!HabitFocusPolicy.canFocus(habit)) return@transactionWithResult FocusTimerResult(FocusTimerOutcome.HABIT_NOT_ACTIVE)
        val active = runCatching {
            ActiveFocusTimer(PlatformIdentity.newId(), "", habit.name.ifBlank { habit.uid }, command.mode,
                command.plannedDurationSeconds, FocusTimerState.RUNNING, now.toString(), now.toString(), 0, now.toString(),
                FocusTargetRef(FocusTargetKind.HABIT, habit.uid), today(now).toString())
        }.getOrNull() ?: return@transactionWithResult FocusTimerResult(FocusTimerOutcome.INVALID_DURATION)
        activeTimers.save(active)
        snapshot(FocusTimerOutcome.APPLIED, active, now)
    }

    fun snapshot(outcome: FocusTimerOutcome, active: ActiveFocusTimer, now: Instant): FocusTimerResult {
        val habit = habit(active)
        val recordContext = habit?.let { context(it, active.habitOccurredOn, now) }
        val base = FocusTimerEngine.snapshot(active, now, habit?.name ?: active.taskTitleSnapshot, HabitFocusPolicy.canFocus(habit))
        return FocusTimerResult(outcome, activeTimer = active, habit = habit,
            snapshot = base.copy(canFinishAndRecord = base.canFinish && recordContext?.canRecord == true,
                habitRecordContext = recordContext))
    }

    fun context(habit: Habit, occurredOn: String?, now: Instant): FocusHabitRecordContext? {
        val date = occurredOn?.let { runCatching { kotlinx.datetime.LocalDate.parse(it) }.getOrNull() } ?: return null
        val records = habits.records(habit.uid)
        val progress = HabitProgressEngine.progress(habit, records, date)
        val decision = HabitFocusPolicy.recordDecision(habit, records, occurredOn, today(now),
            if (habit.tracking.mode == HabitTrackingMode.QUANTITY) 1 else null)
        return FocusHabitRecordContext(habit.uid, habit.name, occurredOn, habit.tracking.mode, habit.tracking.unit,
            progress.completedCount, progress.targetCount,
            decision.outcome in setOf(HabitActionOutcome.APPLIED, HabitActionOutcome.NO_OP), habit.updatedAt)
    }

    fun reconcile(active: ActiveFocusTimer, now: Instant): FocusTimerResult {
        val kind = if (!isActive(active)) FocusCompletionKind.HABIT_BECAME_INACTIVE else FocusCompletionKind.COUNTDOWN_ELAPSED
        val completion = if (kind == FocusCompletionKind.HABIT_BECAME_INACTIVE) FocusTimerEngine.finish(active, now, kind)
            else FocusTimerEngine.reconcile(active, now)
        if (completion.session == null) {
            if (completion.outcome == FocusTimerOutcome.INVALID_DURATION && !isActive(active)) {
                activeTimers.clear()
                return FocusTimerResult(FocusTimerOutcome.APPLIED)
            }
            return snapshot(completion.outcome, active, now)
        }
        database.transaction { sessions.saveInTransaction(completion.session, ChangeOperation.INSERT); activeTimers.clear() }
        mutationNotifier.notifyMutation()
        return FocusTimerResult(FocusTimerOutcome.APPLIED, session = completion.session, habit = habit(active))
    }

    fun finishAndRecord(command: FocusTimerCommand.FinishAndRecordHabit, now: Instant): FocusTimerResult {
        val result = database.transactionWithResult {
            val active = activeTimers.current()
            val existing = sessions.find(command.sessionUid)
            if (existing?.isDeleted == true) return@transactionWithResult FocusTimerResult(FocusTimerOutcome.TIMER_NOT_FOUND)
            val matchingActive = active?.takeIf { it.sessionUid == command.sessionUid && it.boundTarget.kind == FocusTargetKind.HABIT }
            val session = existing ?: matchingActive?.let {
                val kind = if (FocusTimerEngine.calculate(it, now).countdownElapsed) FocusCompletionKind.COUNTDOWN_ELAPSED else FocusCompletionKind.FOCUS_ONLY
                FocusTimerEngine.finish(it, now, kind).session
            } ?: return@transactionWithResult if (active != null) FocusTimerResult(FocusTimerOutcome.INVALID_STATE, activeTimer = active)
                else FocusTimerResult(FocusTimerOutcome.TIMER_NOT_FOUND)
            if (session.boundTarget.kind != FocusTargetKind.HABIT) return@transactionWithResult FocusTimerResult(FocusTimerOutcome.INVALID_STATE)
            if (session.habitRecordedAmount != null) return@transactionWithResult FocusTimerResult(FocusTimerOutcome.NO_OP, activeTimer = active, session = session)
            val habit = habits.find(session.boundTarget.uid)
                ?: return@transactionWithResult failure(FocusTimerOutcome.HABIT_NOT_FOUND, active, session.takeIf { existing != null }, now)
            if (command.expectedHabitUpdatedAt != null && habit.updatedAt != command.expectedHabitUpdatedAt) {
                return@transactionWithResult failure(FocusTimerOutcome.INVALID_STATE, active, session.takeIf { existing != null }, now)
            }
            val date = session.habitOccurredOn ?: return@transactionWithResult FocusTimerResult(FocusTimerOutcome.INVALID_STATE)
            val records = habits.records(habit.uid)
            val decision = HabitFocusPolicy.recordDecision(habit, records, date, today(now), command.amount)
            if (decision.outcome !in setOf(HabitActionOutcome.APPLIED, HabitActionOutcome.NO_OP)) {
                return@transactionWithResult failure(decision.outcome.toFocusOutcome(), active, session.takeIf { existing != null }, now)
            }
            val current = records.filter { !it.isDeleted && it.status == HabitRecordStatus.COMPLETED && it.occurredOn == date }.sumOf { it.count.toLong() }
            val targetCount = requireNotNull(decision.targetCountForDay)
            val added = if (decision.outcome == HabitActionOutcome.APPLIED) (targetCount - current).toInt() else 0
            if (decision.outcome == HabitActionOutcome.APPLIED) {
                val record = habits.executeRecordCommandInTransaction(HabitRecordCommand.Set(habit.uid, date, targetCount), now.toString(), today(now))
                check(record.outcome == HabitActionOutcome.APPLIED) { "Habit changed during focus transaction" }
            }
            val saved = session.copy(completionKind = FocusCompletionKind.FOCUS_AND_HABIT_RECORDED,
                habitRecordedAmount = added, updatedAt = now.toString())
            sessions.saveInTransaction(saved, if (existing == null) ChangeOperation.INSERT else ChangeOperation.UPDATE)
            if (matchingActive != null) activeTimers.clear()
            FocusTimerResult(FocusTimerOutcome.APPLIED, activeTimer = active.takeUnless { matchingActive != null }, session = saved, habit = habit)
        }
        if (result.outcome == FocusTimerOutcome.APPLIED) mutationNotifier.notifyMutation()
        return result
    }

    private fun failure(outcome: FocusTimerOutcome, active: ActiveFocusTimer?, session: FocusSession?, now: Instant): FocusTimerResult =
        if (active != null) FocusTimerResult(outcome, activeTimer = active, session = session) else FocusTimerResult(outcome, session = session)

    private fun today(now: Instant) = now.toLocalDateTime(TimeZone.currentSystemDefault()).date

    private fun HabitActionOutcome.toFocusOutcome() = when (this) {
        HabitActionOutcome.NOT_FOUND -> FocusTimerOutcome.HABIT_NOT_FOUND
        HabitActionOutcome.INACTIVE -> FocusTimerOutcome.HABIT_NOT_ACTIVE
        HabitActionOutcome.NOT_DUE, HabitActionOutcome.FUTURE_DATE -> FocusTimerOutcome.HABIT_NOT_DUE
        HabitActionOutcome.INVALID_AMOUNT -> FocusTimerOutcome.HABIT_INVALID_AMOUNT
        else -> FocusTimerOutcome.INVALID_STATE
    }
}
