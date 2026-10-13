package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitProgressEngine
import com.deadliner.domain.habit.HabitRecordCommand
import com.deadliner.domain.habit.HabitRecordCommandPolicy
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitRecordStatus
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitStatusAction
import com.deadliner.domain.habit.HabitActionOutcome
import com.deadliner.platform.PlatformIdentity
import com.deadliner.model.ChangeOperation
import com.deadliner.sync.RepositoryMutationNotifier
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class HabitRepository(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
    private val mutationNotifier: RepositoryMutationNotifier = RepositoryMutationNotifier.NONE,
    private val activeFocusTimers: com.deadliner.db.repository.focus.ActiveFocusTimerStore? = null,
) {
    fun list(): List<Habit> = database.habitQueries.selectAll(::habitFromSql).executeAsList()
    fun find(uid: String): Habit? = database.habitQueries.selectByUid(uid, ::habitFromSql).executeAsOneOrNull()
    fun records(habitUid: String): List<HabitRecord> = database.habitRecordQueries.selectForHabit(habitUid, ::toRecord).executeAsList()
    /** Notification versions include tombstones so clearing a result cannot revive old actions. */
    fun recordsIncludingDeleted(habitUid: String): List<HabitRecord> = database.habitRecordQueries.selectAllForHabit(habitUid, ::toRecord).executeAsList()
    fun allRecords(): List<HabitRecord> = database.habitRecordQueries.selectAll(::toRecord).executeAsList()
    fun discardedLegacyScheduleItemCount(): Long = database.migrationDiagnosticQueries
        .selectValue("habit_schedule_item_discarded_v5")
        .executeAsOneOrNull() ?: 0L

    fun create(habit: Habit) = save(habit, ChangeOperation.INSERT)
    fun update(habit: Habit) {
        if (habit.isDeleted || habit.status != HabitStatus.ACTIVE) requireFocusResolved(habit.uid)
        save(habit, ChangeOperation.UPDATE)
    }

    fun applyStatusAction(uid: String, action: HabitStatusAction, occurredAt: String): HabitActionResult {
        val result = database.transactionWithResult { applyStatusActionInTransaction(uid, action, occurredAt) }
        if (result.outcome == HabitActionOutcome.APPLIED) mutationNotifier.notifyMutation()
        return result
    }

    internal fun applyStatusActionInTransaction(uid: String, action: HabitStatusAction, occurredAt: String): HabitActionResult {
        val existing = find(uid) ?: return HabitActionResult(null, HabitActionOutcome.NOT_FOUND)
        val target = when (action) {
            HabitStatusAction.ARCHIVE -> HabitStatus.ARCHIVED
            HabitStatusAction.RESTORE -> HabitStatus.ACTIVE
        }
        if (existing.status == target) return HabitActionResult(existing, HabitActionOutcome.NO_OP)
        if (target != HabitStatus.ACTIVE) requireFocusResolved(uid)
        val updated = existing.copy(status = target, updatedAt = occurredAt)
        saveInTransaction(updated, ChangeOperation.UPDATE)
        return HabitActionResult(updated, HabitActionOutcome.APPLIED)
    }

    fun toggleRecord(habitUid: String, occurredOn: String, occurredAt: String): HabitActionResult {
        return executeRecordCommand(HabitRecordCommand.CheckOff(habitUid, occurredOn), occurredAt)
    }

    fun clearRecords(habitUid: String, occurredOn: String, occurredAt: String): HabitActionResult {
        return executeRecordCommand(HabitRecordCommand.Clear(habitUid, occurredOn), occurredAt)
    }

    fun addRecordAmount(habitUid: String, occurredOn: String, amount: Int, occurredAt: String): HabitActionResult {
        return executeRecordCommand(HabitRecordCommand.Add(habitUid, occurredOn, amount), occurredAt)
    }

    fun setRecordAmount(habitUid: String, occurredOn: String, amount: Int, occurredAt: String): HabitActionResult {
        return executeRecordCommand(HabitRecordCommand.Set(habitUid, occurredOn, amount), occurredAt)
    }

    /** Shared cyclic action: advance toward the goal, then reset the completed goal window to zero. */
    fun applyPrimaryRecordAction(habitUid: String, occurredOn: String, occurredAt: String): HabitActionResult {
        return executeRecordCommand(HabitRecordCommand.ApplyPrimary(habitUid, occurredOn), occurredAt)
    }

    fun executeRecordCommand(command: HabitRecordCommand, occurredAt: String): HabitActionResult {
        val result = database.transactionWithResult {
            executeRecordCommandInTransaction(command, occurredAt,
                Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
        }
        if (result.outcome == HabitActionOutcome.APPLIED) mutationNotifier.notifyMutation()
        return result
    }

    internal fun executeRecordCommandInTransaction(command: HabitRecordCommand, occurredAt: String, today: LocalDate): HabitActionResult {
        val habitUid = command.habitUid
        val habit = find(habitUid) ?: return HabitActionResult(null, HabitActionOutcome.NOT_FOUND)
        val current = records(habitUid)
        val decision = HabitRecordCommandPolicy.evaluate(
            habit,
            current,
            command,
            today,
        )
        if (decision.outcome != HabitActionOutcome.APPLIED) return HabitActionResult(habit, decision.outcome)
        val targetCount = decision.targetCountForDay ?: return HabitActionResult(habit, HabitActionOutcome.NO_OP)
        val affectedRange = if (decision.clearGoalWindow) {
            HabitProgressEngine.goalRange(habit, LocalDate.parse(command.occurredOn))
        } else {
            val date = LocalDate.parse(command.occurredOn)
            date..date
        }
        database.transaction {
            current.filter {
                !it.isDeleted &&
                    it.occurredOn in affectedRange.start.toString()..affectedRange.endInclusive.toString() &&
                    it.status == HabitRecordStatus.COMPLETED
            }.forEach { softDeleteRecord(it, occurredAt) }
            if (targetCount > 0) {
                writeRecord(
                    HabitRecord(
                        uid = PlatformIdentity.newId(),
                        habitUid = habitUid,
                        occurredOn = command.occurredOn,
                        count = targetCount,
                        createdAt = occurredAt,
                        updatedAt = occurredAt,
                    ),
                    ChangeOperation.INSERT,
                )
            }
        }
        return HabitActionResult(find(habitUid), decision.outcome)
    }

    fun delete(uid: String, updatedAt: String) {
        database.transaction { deleteInTransaction(uid, updatedAt) }
        mutationNotifier.notifyMutation()
    }

    internal fun deleteInTransaction(uid: String, updatedAt: String) {
        requireFocusResolved(uid)
        database.habitRecordQueries.selectForHabit(uid, ::toRecord).executeAsList().forEach { record ->
            database.habitRecordQueries.softDeleteHabitRecord(updatedAt, record.uid)
            changeLog.appendLocal("habit_record", record.uid, ChangeOperation.DELETE, null)
        }
        database.habitQueries.softDeleteHabit(updatedAt, uid)
        changeLog.appendLocal("habit", uid, ChangeOperation.DELETE, null)
    }

    fun saveRecord(record: HabitRecord, operation: ChangeOperation = ChangeOperation.UPDATE) {
        database.transaction {
            writeRecord(record, operation)
        }
        mutationNotifier.notifyMutation()
    }

    private fun save(habit: Habit, operation: ChangeOperation) {
        database.transaction {
            saveInTransaction(habit, operation)
        }
        mutationNotifier.notifyMutation()
    }

    private fun saveInTransaction(habit: Habit, operation: ChangeOperation) {
        database.upsertHabit(habit)
        changeLog.appendLocal("habit", habit.uid, operation, json.encodeToJsonElement(habit).jsonObject)
    }

    private fun requireFocusResolved(uid: String) {
        val target = activeFocusTimers?.current()?.boundTarget
        check(target?.kind != com.deadliner.domain.focus.FocusTargetKind.HABIT || target.uid != uid) {
            "End the active Habit focus before archiving or deleting it"
        }
    }

    private fun writeRecord(record: HabitRecord, operation: ChangeOperation) {
        database.habitRecordQueries.insertHabitRecord(record.uid, record.habitUid, record.occurredOn, record.count.toLong(), record.status.name, record.createdAt, record.updatedAt, record.isDeleted.asLong())
        database.habitRecordQueries.updateHabitRecord(record.habitUid, record.occurredOn, record.count.toLong(), record.status.name, record.updatedAt, record.isDeleted.asLong(), record.uid)
        changeLog.appendLocal("habit_record", record.uid, operation, json.encodeToJsonElement(record).jsonObject)
    }

    private fun softDeleteRecord(record: HabitRecord, occurredAt: String) {
        database.habitRecordQueries.softDeleteHabitRecord(occurredAt, record.uid)
        changeLog.appendLocal("habit_record", record.uid, ChangeOperation.DELETE, null)
    }

    private fun toRecord(
        uid: String, habitUid: String, occurredOn: String, count: Long, status: String, createdAt: String,
        updatedAt: String, isDeleted: Long,
    ) = HabitRecord(uid, habitUid, occurredOn, count.toInt(), HabitRecordStatus.valueOf(status), createdAt, updatedAt, isDeleted.asBoolean())

}

data class HabitActionResult(val habit: Habit?, val outcome: HabitActionOutcome)
