package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitGoalType
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitRecordStatus
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitScheduleItem
import com.deadliner.domain.habit.HabitScheduleState
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitStatusAction
import com.deadliner.domain.habit.HabitActionOutcome
import com.deadliner.platform.PlatformIdentity
import com.deadliner.model.ChangeOperation
import com.deadliner.sync.RepositoryMutationNotifier
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class HabitRepository(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
    private val mutationNotifier: RepositoryMutationNotifier = RepositoryMutationNotifier.NONE,
) {
    fun list(): List<Habit> = database.habitQueries.selectAll(::toHabit).executeAsList()
    fun find(uid: String): Habit? = database.habitQueries.selectByUid(uid, ::toHabit).executeAsOneOrNull()
    fun records(habitUid: String): List<HabitRecord> = database.habitRecordQueries.selectForHabit(habitUid, ::toRecord).executeAsList()
    fun allRecords(): List<HabitRecord> = database.habitRecordQueries.selectAll(::toRecord).executeAsList()
    fun schedules(habitUid: String): List<HabitScheduleItem> = database.habitScheduleQueries.selectForHabit(habitUid, ::toSchedule).executeAsList()

    fun create(habit: Habit) = save(habit, ChangeOperation.INSERT)
    fun update(habit: Habit) = save(habit, ChangeOperation.UPDATE)

    fun applyStatusAction(uid: String, action: HabitStatusAction, occurredAt: String): HabitActionResult {
        val existing = find(uid) ?: return HabitActionResult(null, HabitActionOutcome.NOT_FOUND)
        val target = when (action) {
            HabitStatusAction.ARCHIVE -> HabitStatus.ARCHIVED
            HabitStatusAction.RESTORE -> HabitStatus.ACTIVE
        }
        if (existing.status == target) return HabitActionResult(existing, HabitActionOutcome.NO_OP)
        val updated = existing.copy(status = target, updatedAt = occurredAt)
        save(updated, ChangeOperation.UPDATE)
        return HabitActionResult(updated, HabitActionOutcome.APPLIED)
    }

    fun toggleRecord(habitUid: String, occurredOn: String, occurredAt: String): HabitActionResult {
        val habit = find(habitUid) ?: return HabitActionResult(null, HabitActionOutcome.NOT_FOUND)
        val current = records(habitUid)
        val sameDay = current.filter { it.occurredOn == occurredOn && it.status == HabitRecordStatus.COMPLETED }
        val remove = when (habit.period) {
            HabitPeriod.ONCE, HabitPeriod.EBBINGHAUS -> sameDay.isNotEmpty()
            HabitPeriod.DAILY -> sameDay.sumOf(HabitRecord::count) >= habit.timesPerPeriod.coerceAtLeast(1)
            HabitPeriod.WEEKLY, HabitPeriod.MONTHLY -> sameDay.isNotEmpty()
        }
        database.transaction {
            if (remove) {
                sameDay.forEach { softDeleteRecord(it, occurredAt) }
            } else if (mayAddRecord(habit, current, occurredOn)) {
                val record = HabitRecord(PlatformIdentity.newId(), habitUid, occurredOn, createdAt = occurredAt, updatedAt = occurredAt)
                writeRecord(record, ChangeOperation.INSERT)
            }
        }
        mutationNotifier.notifyMutation()
        return HabitActionResult(find(habitUid), HabitActionOutcome.APPLIED)
    }

    fun clearRecords(habitUid: String, occurredOn: String, occurredAt: String): HabitActionResult {
        val habit = find(habitUid) ?: return HabitActionResult(null, HabitActionOutcome.NOT_FOUND)
        database.transaction {
            records(habitUid).filter { it.occurredOn == occurredOn }.forEach { softDeleteRecord(it, occurredAt) }
        }
        mutationNotifier.notifyMutation()
        return HabitActionResult(habit, HabitActionOutcome.APPLIED)
    }

    fun delete(uid: String, updatedAt: String) {
        database.transaction {
            database.habitRecordQueries.selectForHabit(uid, ::toRecord).executeAsList().forEach { record ->
                database.habitRecordQueries.softDeleteHabitRecord(updatedAt, record.uid)
                changeLog.appendLocal("habit_record", record.uid, ChangeOperation.DELETE, null)
            }
            database.habitScheduleQueries.selectForHabit(uid, ::toSchedule).executeAsList().forEach { schedule ->
                database.habitScheduleQueries.softDeleteHabitSchedule(updatedAt, schedule.uid)
                changeLog.appendLocal("habit_schedule_item", schedule.uid, ChangeOperation.DELETE, null)
            }
            database.habitQueries.softDeleteHabit(updatedAt, uid)
            changeLog.appendLocal("habit", uid, ChangeOperation.DELETE, null)
        }
        mutationNotifier.notifyMutation()
    }

    fun saveRecord(record: HabitRecord, operation: ChangeOperation = ChangeOperation.UPDATE) {
        database.transaction {
            writeRecord(record, operation)
        }
        mutationNotifier.notifyMutation()
    }

    fun saveSchedule(item: HabitScheduleItem, operation: ChangeOperation = ChangeOperation.UPDATE) {
        database.transaction {
            database.habitScheduleQueries.insertHabitSchedule(
                item.uid, item.habitUid, item.dueAt, item.state.name, item.createdAt, item.updatedAt, item.isDeleted.asLong(),
            )
            database.habitScheduleQueries.updateHabitSchedule(
                item.habitUid, item.dueAt, item.state.name, item.updatedAt, item.isDeleted.asLong(), item.uid,
            )
            changeLog.appendLocal("habit_schedule_item", item.uid, operation, json.encodeToJsonElement(item).jsonObject)
        }
        mutationNotifier.notifyMutation()
    }

    private fun save(habit: Habit, operation: ChangeOperation) {
        database.transaction {
            database.ensureCategoryReference(habit.categoryUid, habit.updatedAt)
            database.habitQueries.insertHabit(
                habit.uid, habit.name, habit.description, habit.color?.toLong(), habit.iconKey, habit.categoryUid,
                habit.period.name, habit.timesPerPeriod.toLong(), habit.goalType.name, habit.totalTarget?.toLong(),
                habit.status.name, habit.sortOrder.toLong(), habit.reminder?.localTime,
                habit.reminder?.isEnabled?.asLong(), habit.createdAt, habit.updatedAt, habit.isDeleted.asLong(),
            )
            database.habitQueries.updateHabit(
                habit.name, habit.description, habit.color?.toLong(), habit.iconKey, habit.categoryUid, habit.period.name,
                habit.timesPerPeriod.toLong(), habit.goalType.name, habit.totalTarget?.toLong(), habit.status.name,
                habit.sortOrder.toLong(), habit.reminder?.localTime, habit.reminder?.isEnabled?.asLong(), habit.updatedAt,
                habit.isDeleted.asLong(), habit.uid,
            )
            changeLog.appendLocal("habit", habit.uid, operation, json.encodeToJsonElement(habit).jsonObject)
        }
        mutationNotifier.notifyMutation()
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

    private fun mayAddRecord(habit: Habit, all: List<HabitRecord>, date: String): Boolean {
        if (habit.goalType == HabitGoalType.TOTAL) return true
        val target = habit.timesPerPeriod.coerceAtLeast(1)
        return when (habit.period) {
            HabitPeriod.DAILY, HabitPeriod.ONCE, HabitPeriod.EBBINGHAUS -> true
            HabitPeriod.WEEKLY, HabitPeriod.MONTHLY -> all.filter { it.status == HabitRecordStatus.COMPLETED && it.occurredOn in periodRange(habit.period, date) }.sumOf(HabitRecord::count) < target
        }
    }

    private fun periodRange(period: HabitPeriod, raw: String): ClosedRange<String> {
        val date = LocalDate.parse(raw)
        val start = when (period) {
            HabitPeriod.WEEKLY -> date.minus(DatePeriod(days = date.dayOfWeek.ordinal))
            HabitPeriod.MONTHLY -> LocalDate(date.year, date.month, 1)
            else -> date
        }
        val end = when (period) {
            HabitPeriod.WEEKLY -> start.plus(DatePeriod(days = 6))
            HabitPeriod.MONTHLY -> start.plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
            else -> start
        }
        return start.toString()..end.toString()
    }

    private fun toHabit(
        uid: String, name: String, description: String?, color: Long?, iconKey: String?, categoryUid: String?,
        period: String, timesPerPeriod: Long, goalType: String, totalTarget: Long?, status: String, sortOrder: Long,
        reminderLocalTime: String?, reminderEnabled: Long?, createdAt: String, updatedAt: String, isDeleted: Long,
    ) = Habit(
        uid, name, description, color?.toInt(), iconKey, categoryUid, HabitPeriod.valueOf(period),
        timesPerPeriod.toInt(), HabitGoalType.valueOf(goalType), totalTarget?.toInt(), HabitStatus.valueOf(status),
        sortOrder.toInt(), reminderLocalTime?.let { HabitReminder(it, reminderEnabled?.asBoolean() ?: true) },
        createdAt, updatedAt, isDeleted.asBoolean(),
    )

    private fun toRecord(
        uid: String, habitUid: String, occurredOn: String, count: Long, status: String, createdAt: String,
        updatedAt: String, isDeleted: Long,
    ) = HabitRecord(uid, habitUid, occurredOn, count.toInt(), HabitRecordStatus.valueOf(status), createdAt, updatedAt, isDeleted.asBoolean())

    private fun toSchedule(
        uid: String, habitUid: String, dueAt: String, state: String, createdAt: String, updatedAt: String, isDeleted: Long,
    ) = HabitScheduleItem(uid, habitUid, dueAt, HabitScheduleState.valueOf(state), createdAt, updatedAt, isDeleted.asBoolean())
}

data class HabitActionResult(val habit: Habit?, val outcome: HabitActionOutcome)
