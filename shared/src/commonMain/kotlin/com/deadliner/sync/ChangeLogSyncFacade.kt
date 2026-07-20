package com.deadliner.sync

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.ChangeLogRepository
import com.deadliner.db.repository.asLong
import com.deadliner.domain.category.Category
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitScheduleItem
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.model.ChangeLogEntry
import com.deadliner.model.ChangeOperation
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Transport-neutral bridge for app adapters. Every remote business write and its changelog
 * acknowledgement share one SQL transaction, so iOS never needs direct database access.
 */
class ChangeLogSyncFacade(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun mergeRemotePayload(remotePayload: String?): ChangeLogSyncMergeResult {
        val pending = changeLog.pending()
        val remote = try {
            remotePayload
                ?.takeIf(String::isNotBlank)
                ?.let { json.decodeFromString<ChangeLogSyncPayload>(it).changes }
                .orEmpty()
        } catch (error: Exception) {
            return ChangeLogSyncMergeResult(
                payload = "",
                pendingIdsPayload = "[]",
                remoteApplied = 0,
                remoteIgnored = 0,
                failureMessage = "Invalid KMP sync payload: ${error.message ?: error::class.simpleName}",
            )
        }
        var applied = 0
        var ignored = 0

        remote.sortedWith(compareBy(::tableOrder, ChangeLogEntry::clientTimestamp, ChangeLogEntry::id)).forEach { entry ->
            val existing = changeLog.latestFor(entry.tableName, entry.recordId)
            if (LastWriteWinsConflictResolver.shouldApply(entry, existing)) {
                applyRemote(entry)
                applied++
            } else {
                changeLog.recordRemote(entry)
                ignored++
            }
        }

        val merged = (remote + pending)
            .associateBy(ChangeLogEntry::id)
            .values
            .sortedWith(compareBy(ChangeLogEntry::clientTimestamp, ChangeLogEntry::id))

        return ChangeLogSyncMergeResult(
            payload = json.encodeToString(ChangeLogSyncPayload(changes = merged)),
            pendingIdsPayload = json.encodeToString(pending.map(ChangeLogEntry::id)),
            remoteApplied = applied,
            remoteIgnored = ignored,
            failureMessage = null,
        )
    }

    fun acknowledgePending(pendingIdsPayload: String, serverTimestamp: Long) {
        changeLog.markSynced(json.decodeFromString(pendingIdsPayload), serverTimestamp)
    }

    private fun applyRemote(entry: ChangeLogEntry) {
        database.transaction {
            when (entry.tableName) {
                "category" -> applyCategory(entry)
                "task" -> applyTask(entry)
                "task_subtask" -> applyTaskSubtask(entry)
                "habit" -> applyHabit(entry)
                "habit_record" -> applyHabitRecord(entry)
                "habit_schedule_item" -> applyHabitSchedule(entry)
            }
            changeLog.recordRemote(entry)
        }
    }

    private fun applyCategory(entry: ChangeLogEntry) {
        if (entry.operation == ChangeOperation.DELETE) {
            database.categoryQueries.softDeleteCategory(remoteUpdatedAt(), entry.recordId)
            return
        }
        val category = entry.document<Category>() ?: return
        database.categoryQueries.insertCategory(
            category.uid, category.name, category.iconKey, category.colorHex, category.isPreset.asLong(),
            category.sortOrder.toLong(), category.createdAt, category.updatedAt, category.isDeleted.asLong(),
        )
        database.categoryQueries.updateCategory(
            category.name, category.iconKey, category.colorHex, category.isPreset.asLong(), category.sortOrder.toLong(),
            category.updatedAt, category.isDeleted.asLong(), category.uid,
        )
    }

    private fun applyTask(entry: ChangeLogEntry) {
        if (entry.operation == ChangeOperation.DELETE) {
            val updatedAt = remoteUpdatedAt()
            database.taskSubtaskQueries.selectAllForTask(entry.recordId, ::toSubtask).executeAsList().forEach {
                database.taskSubtaskQueries.softDeleteSubtask(updatedAt, it.uid)
            }
            database.taskQueries.softDeleteTask(updatedAt, entry.recordId)
            return
        }
        val task = entry.document<Task>() ?: return
        saveTask(task)
    }

    private fun applyTaskSubtask(entry: ChangeLogEntry) {
        if (entry.operation == ChangeOperation.DELETE) {
            database.taskSubtaskQueries.softDeleteSubtask(remoteUpdatedAt(), entry.recordId)
            return
        }
        val subtask = entry.document<TaskSubtask>() ?: return
        database.taskSubtaskQueries.insertSubtask(
            subtask.uid, subtask.taskUid, subtask.content, subtask.isCompleted.asLong(), subtask.sortOrder.toLong(),
            subtask.createdAt, subtask.updatedAt, subtask.isDeleted.asLong(),
        )
        database.taskSubtaskQueries.updateSubtask(
            subtask.taskUid, subtask.content, subtask.isCompleted.asLong(), subtask.sortOrder.toLong(),
            subtask.updatedAt, subtask.isDeleted.asLong(), subtask.uid,
        )
    }

    private fun saveTask(task: Task) {
        database.taskQueries.insertTask(
            task.uid, task.title, task.note, task.startAt, task.dueAt, task.state.persistedValue(), task.completedAt,
            task.categoryUid, task.isStarred.asLong(), task.calendarEventId, task.createdAt, task.updatedAt, task.isDeleted.asLong(),
        )
        database.taskQueries.updateTask(
            task.title, task.note, task.startAt, task.dueAt, task.state.persistedValue(), task.completedAt,
            task.categoryUid, task.isStarred.asLong(), task.calendarEventId, task.updatedAt, task.isDeleted.asLong(), task.uid,
        )
        task.subtasks.forEach { subtask ->
            database.taskSubtaskQueries.insertSubtask(
                subtask.uid, subtask.taskUid, subtask.content, subtask.isCompleted.asLong(), subtask.sortOrder.toLong(),
                subtask.createdAt, subtask.updatedAt, subtask.isDeleted.asLong(),
            )
            database.taskSubtaskQueries.updateSubtask(
                subtask.taskUid, subtask.content, subtask.isCompleted.asLong(), subtask.sortOrder.toLong(),
                subtask.updatedAt, subtask.isDeleted.asLong(), subtask.uid,
            )
        }
    }

    private fun applyHabit(entry: ChangeLogEntry) {
        if (entry.operation == ChangeOperation.DELETE) {
            database.habitQueries.softDeleteHabit(remoteUpdatedAt(), entry.recordId)
            return
        }
        val habit = entry.document<Habit>() ?: return
        database.habitQueries.insertHabit(
            habit.uid, habit.name, habit.description, habit.color?.toLong(), habit.iconKey, habit.categoryUid,
            habit.period.name, habit.timesPerPeriod.toLong(), habit.goalType.name, habit.totalTarget?.toLong(), habit.status.name,
            habit.sortOrder.toLong(), habit.reminder?.localTime, habit.reminder?.isEnabled?.asLong(), habit.createdAt, habit.updatedAt, habit.isDeleted.asLong(),
        )
        database.habitQueries.updateHabit(
            habit.name, habit.description, habit.color?.toLong(), habit.iconKey, habit.categoryUid, habit.period.name,
            habit.timesPerPeriod.toLong(), habit.goalType.name, habit.totalTarget?.toLong(), habit.status.name, habit.sortOrder.toLong(),
            habit.reminder?.localTime, habit.reminder?.isEnabled?.asLong(), habit.updatedAt, habit.isDeleted.asLong(), habit.uid,
        )
    }

    private fun applyHabitRecord(entry: ChangeLogEntry) {
        if (entry.operation == ChangeOperation.DELETE) {
            database.habitRecordQueries.softDeleteHabitRecord(remoteUpdatedAt(), entry.recordId)
            return
        }
        val record = entry.document<HabitRecord>() ?: return
        database.habitRecordQueries.insertHabitRecord(
            record.uid, record.habitUid, record.occurredOn, record.count.toLong(), record.status.name,
            record.createdAt, record.updatedAt, record.isDeleted.asLong(),
        )
        database.habitRecordQueries.updateHabitRecord(
            record.habitUid, record.occurredOn, record.count.toLong(), record.status.name,
            record.updatedAt, record.isDeleted.asLong(), record.uid,
        )
    }

    private fun applyHabitSchedule(entry: ChangeLogEntry) {
        if (entry.operation == ChangeOperation.DELETE) {
            database.habitScheduleQueries.softDeleteHabitSchedule(remoteUpdatedAt(), entry.recordId)
            return
        }
        val schedule = entry.document<HabitScheduleItem>() ?: return
        database.habitScheduleQueries.insertHabitSchedule(
            schedule.uid, schedule.habitUid, schedule.dueAt, schedule.state.name, schedule.createdAt, schedule.updatedAt, schedule.isDeleted.asLong(),
        )
        database.habitScheduleQueries.updateHabitSchedule(
            schedule.habitUid, schedule.dueAt, schedule.state.name, schedule.updatedAt, schedule.isDeleted.asLong(), schedule.uid,
        )
    }

    private inline fun <reified T> ChangeLogEntry.document(): T? =
        changedFields?.let { json.decodeFromJsonElement<T>(it) }

    private fun remoteUpdatedAt(): String = Clock.System.now().toString()

    private fun tableOrder(entry: ChangeLogEntry): Int = when (entry.tableName) {
        "category" -> 0
        "task" -> 1
        "task_subtask" -> 2
        "habit" -> 3
        "habit_record" -> 4
        "habit_schedule_item" -> 5
        else -> 6
    }

    private fun toSubtask(
        uid: String, taskUid: String, content: String, isCompleted: Long, sortOrder: Long,
        createdAt: String, updatedAt: String, isDeleted: Long,
    ) = TaskSubtask(uid, taskUid, content, isCompleted != 0L, sortOrder.toInt(), createdAt, updatedAt, isDeleted != 0L)

    private fun TaskState.persistedValue(): String = when (this) {
        TaskState.ACTIVE -> "active"
        TaskState.COMPLETED -> "completed"
        TaskState.ARCHIVED -> "archived"
        TaskState.ABANDONED -> "abandoned"
        TaskState.ABANDONED_ARCHIVED -> "abandonedArchived"
    }
}

@Serializable
data class ChangeLogSyncPayload(
    val formatVersion: Int = 1,
    val changes: List<ChangeLogEntry>,
)

data class ChangeLogSyncMergeResult(
    val payload: String,
    val pendingIdsPayload: String,
    val remoteApplied: Int,
    val remoteIgnored: Int,
    val failureMessage: String? = null,
)
