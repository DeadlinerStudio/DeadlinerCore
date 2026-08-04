package com.deadliner.sync

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.ChangeLogRepository
import com.deadliner.db.repository.asLong
import com.deadliner.domain.category.Category
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitScheduleItem
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.model.ChangeLogEntry
import com.deadliner.model.ChangeOperation
import com.deadliner.sync.snapshot.SnapshotV3Root
import com.deadliner.sync.snapshot.SnapshotV3Store
import com.deadliner.sync.snapshot.SnapshotV3Validator
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Compatibility façade retained for the existing app bridge. V3 transport uses only the typed
 * full snapshot; the historical change-log repository remains local audit state.
 */
class ChangeLogSyncFacade(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val snapshotStore = SnapshotV3Store(database)

    fun mergeRemotePayload(remotePayload: String?): ChangeLogSyncMergeResult {
        val remotePayloadModel = try {
            remotePayload
                ?.takeIf(String::isNotBlank)
                ?.let { json.decodeFromString<ChangeLogSyncPayload>(it).also { payload ->
                    payload.snapshot?.let(SnapshotV3Validator::requireValid)
                } }
                ?: ChangeLogSyncPayload()
        } catch (error: Exception) {
            return ChangeLogSyncMergeResult(
                payload = "",
                pendingIdsPayload = "[]",
                remoteApplied = 0,
                remoteIgnored = 0,
                failureMessage = "Invalid KMP sync payload: ${error.message ?: error::class.simpleName}",
            )
        }
        return try {
            snapshotStore.merge(remotePayloadModel.snapshot).let { merged ->
                ChangeLogSyncMergeResult(
                    payload = json.encodeToString(ChangeLogSyncPayload(snapshot = merged.snapshot)),
                    pendingIdsPayload = "[]",
                    remoteApplied = merged.remoteApplied,
                    remoteIgnored = merged.remoteIgnored,
                )
            }
        } catch (error: Throwable) {
            ChangeLogSyncMergeResult(
                payload = "",
                pendingIdsPayload = "[]",
                remoteApplied = 0,
                remoteIgnored = 0,
                failureMessage = "Failed to merge V3 snapshot: ${error.message ?: error::class.simpleName}",
            )
        }
    }

    /** V3 no longer uploads a log, so no local log rows are acknowledged by network success. */
    fun acknowledgePending(pendingIdsPayload: String, serverTimestamp: Long) = Unit

    private fun applyRemote(entry: ChangeLogEntry) {
        database.transaction {
            when (entry.tableName) {
                "category" -> applyCategory(entry)
                "task" -> applyTask(entry)
                "task_subtask" -> applyTaskSubtask(entry)
                "habit" -> applyHabit(entry)
                "habit_record" -> applyHabitRecord(entry)
                "habit_schedule_item" -> applyHabitSchedule(entry)
                "capture_item" -> applyCapture(entry)
                "memory_fragment" -> applyMemory(entry)
                "user_profile" -> applyUserProfile(entry)
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
        ensureCategoryReference(task.categoryUid, task.updatedAt)
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
        ensureCategoryReference(habit.categoryUid, habit.updatedAt)
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

    private fun applyCapture(entry: ChangeLogEntry) {
        if (entry.operation == ChangeOperation.DELETE) {
            database.captureQueries.softDeleteCapture(remoteUpdatedAt(), entry.recordId)
            return
        }
        val capture = entry.document<CaptureItem>() ?: return
        database.captureQueries.insertCapture(capture.uid, capture.text, capture.source.name, capture.createdAt, capture.updatedAt, capture.isConsumed.asLong(), capture.isDeleted.asLong())
        database.captureQueries.updateCapture(capture.text, capture.source.name, capture.updatedAt, capture.isConsumed.asLong(), capture.isDeleted.asLong(), capture.uid)
    }

    private fun applyMemory(entry: ChangeLogEntry) {
        if (entry.operation == ChangeOperation.DELETE) {
            database.memoryQueries.softDeleteMemory(remoteUpdatedAt(), entry.recordId)
            return
        }
        val memory = entry.document<com.deadliner.domain.memory.MemoryFragment>() ?: return
        database.memoryQueries.insertMemory(memory.uid, memory.content, memory.category, memory.createdAt, memory.importance.toLong(), memory.updatedAt, memory.isDeleted.asLong())
        database.memoryQueries.updateMemory(memory.content, memory.category, memory.importance.toLong(), memory.updatedAt, memory.isDeleted.asLong(), memory.uid)
    }

    private fun applyUserProfile(entry: ChangeLogEntry) {
        if (entry.operation == ChangeOperation.DELETE) {
            database.userProfileQueries.softDeleteUserProfile(remoteUpdatedAt(), entry.recordId)
            return
        }
        val profile = entry.document<com.deadliner.domain.account.UserProfile>() ?: return
        database.userProfileQueries.insertUserProfile(profile.uid, profile.nickname, profile.avatarFileName, profile.tier.name, profile.createdAt, profile.updatedAt, profile.isDeleted.asLong())
        database.userProfileQueries.updateUserProfile(profile.nickname, profile.avatarFileName, profile.tier.name, profile.updatedAt, profile.isDeleted.asLong(), profile.uid)
    }

    private inline fun <reified T> ChangeLogEntry.document(): T? =
        changedFields?.let { json.decodeFromJsonElement<T>(it) }

    private fun remoteUpdatedAt(): String = Clock.System.now().toString()

    /**
     * Category UI is intentionally absent on HarmonyOS during the migration, but tasks and
     * habits must retain the category UID received from iOS. A historical category can predate
     * the changelog blob, so create a local non-changelog placeholder until its authoritative
     * category entry arrives. This preserves the relationship without manufacturing a local
     * change that could overwrite iOS metadata.
     */
    private fun ensureCategoryReference(categoryUid: String?, timestamp: String) {
        if (categoryUid == null) return
        database.categoryQueries.insertCategory(
            categoryUid,
            "未命名分类",
            "folder",
            "#808080",
            false.asLong(),
            Int.MAX_VALUE.toLong(),
            timestamp,
            timestamp,
            false.asLong(),
        )
    }

    private fun remoteApplyFailureMessage(entry: ChangeLogEntry, error: Throwable): String {
        val reference = when (entry.tableName) {
            "task_subtask" -> entry.document<TaskSubtask>()?.taskUid?.let { "task/$it" }
            "habit_record" -> entry.document<HabitRecord>()?.habitUid?.let { "habit/$it" }
            "habit_schedule_item" -> entry.document<HabitScheduleItem>()?.habitUid?.let { "habit/$it" }
            else -> null
        }
        return buildString {
            append("Failed to apply remote ")
            append(entry.tableName)
            append('/')
            append(entry.recordId)
            if (reference != null) {
                append("; required parent ")
                append(reference)
            }
            append(": ")
            append(error.message ?: error::class.simpleName ?: "unknown SQLite failure")
        }
    }

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
    val formatVersion: Int = 3,
    val snapshot: SnapshotV3Root? = null,
    /** Read-only compatibility field for the unsafe V2 hybrid payload; V3 never consumes it. */
    val changes: List<ChangeLogEntry> = emptyList(),
)

data class ChangeLogSyncMergeResult(
    val payload: String,
    val pendingIdsPayload: String,
    val remoteApplied: Int,
    val remoteIgnored: Int,
    val failureMessage: String? = null,
)
