package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.task.TaskActionOutcome
import com.deadliner.domain.task.TaskState
import com.deadliner.domain.task.TaskStateMachine
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.model.ChangeOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class TaskRepository(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
) {
    fun list(): List<Task> = database.taskQueries.selectAll(::toTask).executeAsList().map(::withSubtasks)

    fun find(uid: String): Task? = database.taskQueries.selectByUid(uid, ::toTask).executeAsOneOrNull()?.let(::withSubtasks)

    fun create(task: Task) = save(task, ChangeOperation.INSERT)

    fun update(task: Task) = save(task, ChangeOperation.UPDATE)

    /** KMP is the sole authority for persisted task-state transitions. */
    fun applyAction(uid: String, action: TaskAction, occurredAt: String): TaskActionResult {
        val existing = find(uid) ?: return TaskActionResult(null, TaskActionOutcome.NOT_FOUND)
        val target = TaskStateMachine.apply(existing.state, action)
        if (target == null) {
            return TaskActionResult(
                existing,
                if (TaskStateMachine.isNoOp(existing.state, action)) TaskActionOutcome.NO_OP else TaskActionOutcome.INVALID,
            )
        }
        val updated = existing.copy(
            state = target,
            completedAt = completedAtAfter(existing, target, occurredAt),
            updatedAt = occurredAt,
        )
        save(updated, ChangeOperation.UPDATE)
        return TaskActionResult(updated, TaskActionOutcome.APPLIED)
    }

    fun delete(uid: String, updatedAt: String) {
        database.transaction {
            database.taskSubtaskQueries.selectAllForTask(uid, ::toSubtask).executeAsList().forEach { subtask ->
                if (!subtask.isDeleted) {
                    database.taskSubtaskQueries.softDeleteSubtask(updatedAt, subtask.uid)
                    changeLog.appendLocal("task_subtask", subtask.uid, ChangeOperation.DELETE, null)
                }
            }
            database.taskQueries.softDeleteTask(updatedAt, uid)
            changeLog.appendLocal("task", uid, ChangeOperation.DELETE, null)
        }
    }

    private fun save(task: Task, operation: ChangeOperation) {
        database.transaction {
            ensureCategoryReference(task.categoryUid, task.updatedAt)
            database.taskQueries.insertTask(
                task.uid, task.title, task.note, task.startAt, task.dueAt, task.state.persistedValue(),
                task.completedAt, task.categoryUid, task.isStarred.asLong(), task.calendarEventId,
                task.createdAt, task.updatedAt, task.isDeleted.asLong(),
            )
            database.taskQueries.updateTask(
                task.title, task.note, task.startAt, task.dueAt, task.state.persistedValue(), task.completedAt,
                task.categoryUid, task.isStarred.asLong(), task.calendarEventId, task.updatedAt,
                task.isDeleted.asLong(), task.uid,
            )
            task.subtasks.forEach { subtask ->
                database.taskSubtaskQueries.insertSubtask(
                    subtask.uid, subtask.taskUid, subtask.content, subtask.isCompleted.asLong(),
                    subtask.sortOrder.toLong(), subtask.createdAt, subtask.updatedAt, subtask.isDeleted.asLong(),
                )
                database.taskSubtaskQueries.updateSubtask(
                    subtask.taskUid, subtask.content, subtask.isCompleted.asLong(), subtask.sortOrder.toLong(),
                    subtask.updatedAt, subtask.isDeleted.asLong(), subtask.uid,
                )
                changeLog.appendLocal("task_subtask", subtask.uid, operation, json.encodeToJsonElement(subtask).jsonObject)
            }
            changeLog.appendLocal("task", task.uid, operation, json.encodeToJsonElement(task).jsonObject)
        }
    }

    /**
     * Categories are not rendered on HarmonyOS yet, but KMP still retains their identifiers so
     * that iOS metadata is not lost. A historical task may arrive before the category change-log
     * entry (or the category may predate changelog sync altogether). Preserve the relationship by
     * creating a local placeholder; the authoritative category record later updates it in place.
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

    private fun completedAtAfter(current: Task, target: TaskState, occurredAt: String): String? = when (target) {
        TaskState.COMPLETED -> occurredAt
        TaskState.ARCHIVED -> current.completedAt
        TaskState.ACTIVE, TaskState.ABANDONED, TaskState.ABANDONED_ARCHIVED -> null
    }

    private fun withSubtasks(task: Task): Task = task.copy(
        subtasks = database.taskSubtaskQueries.selectForTask(task.uid, ::toSubtask).executeAsList(),
    )

    private fun toTask(
        uid: String, title: String, note: String, startAt: String?, dueAt: String?, state: String,
        completedAt: String?, categoryUid: String?, isStarred: Long, calendarEventId: Long?,
        createdAt: String, updatedAt: String, isDeleted: Long,
    ) = Task(
        uid = uid, title = title, note = note, startAt = startAt, dueAt = dueAt,
        state = state.toTaskState(), completedAt = completedAt, categoryUid = categoryUid,
        isStarred = isStarred.asBoolean(), calendarEventId = calendarEventId,
        createdAt = createdAt, updatedAt = updatedAt, isDeleted = isDeleted.asBoolean(),
    )

    private fun toSubtask(
        uid: String, taskUid: String, content: String, isCompleted: Long, sortOrder: Long,
        createdAt: String, updatedAt: String, isDeleted: Long,
    ) = TaskSubtask(uid, taskUid, content, isCompleted.asBoolean(), sortOrder.toInt(), createdAt, updatedAt, isDeleted.asBoolean())
}

data class TaskActionResult(
    val task: Task?,
    val outcome: TaskActionOutcome,
)

private fun TaskState.persistedValue(): String = when (this) {
    TaskState.ACTIVE -> "active"
    TaskState.COMPLETED -> "completed"
    TaskState.ARCHIVED -> "archived"
    TaskState.ABANDONED -> "abandoned"
    TaskState.ABANDONED_ARCHIVED -> "abandonedArchived"
}

private fun String.toTaskState(): TaskState = when (this) {
    "active" -> TaskState.ACTIVE
    "completed" -> TaskState.COMPLETED
    "archived" -> TaskState.ARCHIVED
    "abandoned" -> TaskState.ABANDONED
    "abandonedArchived" -> TaskState.ABANDONED_ARCHIVED
    else -> error("Unknown persisted TaskState: $this")
}
