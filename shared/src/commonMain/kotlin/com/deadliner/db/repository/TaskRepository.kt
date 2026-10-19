package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.focus.ActiveFocusTimerStore
import com.deadliner.domain.focus.FocusTaskPolicy
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskDeadlineAdjustmentCommands
import com.deadliner.domain.task.TaskDeadlineDelay
import com.deadliner.domain.task.TaskDeadlineUnit
import com.deadliner.domain.task.TaskDeadlineOutcome
import com.deadliner.domain.task.TaskDeadlineResult
import com.deadliner.domain.task.TaskDeadlineShift
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.task.TaskActionOutcome
import com.deadliner.domain.task.TaskState
import com.deadliner.domain.task.TaskStateMachine
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.model.ChangeOperation
import com.deadliner.sync.RepositoryMutationNotifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class TaskRepository(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
    private val mutationNotifier: RepositoryMutationNotifier = RepositoryMutationNotifier.NONE,
    private val activeTimers: ActiveFocusTimerStore = ActiveFocusTimerStore(database),
) : TaskDeadlineAdjustmentCommands {
    fun list(): List<Task> = database.taskQueries.selectAll(::toTask).executeAsList().map(::withSubtasks)

    fun find(uid: String): Task? = database.taskQueries.selectByUid(uid, ::toTask).executeAsOneOrNull()?.let(::withSubtasks)

    fun create(task: Task) = save(task, ChangeOperation.INSERT)

    fun update(task: Task): TaskActionResult {
        var result: TaskActionResult? = null
        database.transaction {
            val existing = find(task.uid)
            result = if (existing != null && FocusTaskPolicy.requiresResolution(existing, task, activeTimers.current())) {
                TaskActionResult(existing, TaskActionOutcome.TIMER_REQUIRES_RESOLUTION)
            } else {
                saveInTransaction(task, ChangeOperation.UPDATE)
                TaskActionResult(task, TaskActionOutcome.APPLIED)
            }
        }
        return requireNotNull(result).also {
            if (it.outcome == TaskActionOutcome.APPLIED) mutationNotifier.notifyMutation()
        }
    }

    /** Reads and updates dueAt in one transaction so repeated commands accumulate safely. */
    override fun postponeOneDay(taskUid: String, occurredAt: String): TaskDeadlineResult =
        postpone(taskUid, TaskDeadlineDelay(1, TaskDeadlineUnit.DAYS), occurredAt)

    override fun postpone(taskUid: String, delay: TaskDeadlineDelay, occurredAt: String): TaskDeadlineResult {
        if (!delay.isValid) return TaskDeadlineResult(null, TaskDeadlineOutcome.INVALID_DELAY)
        var result: TaskDeadlineResult? = null
        database.transaction {
            val current = find(taskUid)
            val dueAt = TaskDeadlineShift.postponed(current?.dueAt, delay)
            result = when {
                current == null || current.isDeleted -> TaskDeadlineResult(null, TaskDeadlineOutcome.NOT_FOUND)
                dueAt == null -> TaskDeadlineResult(current, TaskDeadlineOutcome.INVALID_DEADLINE)
                else -> {
                    val updated = current.copy(dueAt = dueAt, updatedAt = occurredAt)
                    saveInTransaction(updated, ChangeOperation.UPDATE)
                    TaskDeadlineResult(updated, TaskDeadlineOutcome.APPLIED)
                }
            }
        }
        return requireNotNull(result).also {
            if (it.outcome == TaskDeadlineOutcome.APPLIED) mutationNotifier.notifyMutation()
        }
    }

    /** KMP is the sole authority for persisted task-state transitions. */
    fun applyAction(uid: String, action: TaskAction, occurredAt: String): TaskActionResult {
        var result: TaskActionResult? = null
        database.transaction {
            val existing = find(uid)
            result = when {
                existing == null -> TaskActionResult(null, TaskActionOutcome.NOT_FOUND)
                FocusTaskPolicy.requiresResolution(existing, action, activeTimers.current()) ->
                    TaskActionResult(existing, TaskActionOutcome.TIMER_REQUIRES_RESOLUTION)
                else -> applyActionInTransaction(uid, action, occurredAt)
            }
        }
        return requireNotNull(result).also {
            if (it.outcome == TaskActionOutcome.APPLIED) mutationNotifier.notifyMutation()
        }
    }

    /** Reuses the Task state machine inside a larger business transaction without notifying early. */
    internal fun applyActionInTransaction(uid: String, action: TaskAction, occurredAt: String): TaskActionResult {
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
        saveInTransaction(updated, ChangeOperation.UPDATE)
        return TaskActionResult(updated, TaskActionOutcome.APPLIED)
    }

    fun delete(uid: String, updatedAt: String): TaskActionResult {
        var result: TaskActionResult? = null
        database.transaction {
            val existing = find(uid)
            result = when {
                existing == null -> TaskActionResult(null, TaskActionOutcome.NOT_FOUND)
                FocusTaskPolicy.requiresResolutionForDelete(uid, activeTimers.current()) ->
                    TaskActionResult(existing, TaskActionOutcome.TIMER_REQUIRES_RESOLUTION)
                else -> {
                    deleteInTransaction(uid, updatedAt)
                    TaskActionResult(existing.copy(updatedAt = updatedAt, isDeleted = true), TaskActionOutcome.APPLIED)
                }
            }
        }
        return requireNotNull(result).also {
            if (it.outcome == TaskActionOutcome.APPLIED) mutationNotifier.notifyMutation()
        }
    }

    internal fun deleteInTransaction(uid: String, updatedAt: String) {
        database.taskSubtaskQueries.selectAllForTask(uid, ::toSubtask).executeAsList().forEach { subtask ->
            if (!subtask.isDeleted) {
                database.taskSubtaskQueries.softDeleteSubtask(updatedAt, subtask.uid)
                changeLog.appendLocal("task_subtask", subtask.uid, ChangeOperation.DELETE, null)
            }
        }
        database.taskQueries.softDeleteTask(updatedAt, uid)
        changeLog.appendLocal("task", uid, ChangeOperation.DELETE, null)
    }

    private fun save(task: Task, operation: ChangeOperation) {
        database.transaction {
            saveInTransaction(task, operation)
        }
        mutationNotifier.notifyMutation()
    }

    private fun saveInTransaction(task: Task, operation: ChangeOperation) {
        database.ensureCategoryReference(task.categoryUid, task.updatedAt)
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
