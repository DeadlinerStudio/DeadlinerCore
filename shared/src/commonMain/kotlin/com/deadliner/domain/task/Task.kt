package com.deadliner.domain.task

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Task(
    val uid: String,
    val title: String,
    val note: String = "",
    val startAt: String? = null,
    val dueAt: String? = null,
    val state: TaskState = TaskState.ACTIVE,
    val completedAt: String? = null,
    val categoryUid: String? = null,
    val isStarred: Boolean = false,
    val calendarEventId: Long? = null,
    val createdAt: String,
    val updatedAt: String,
    val isDeleted: Boolean = false,
    val subtasks: List<TaskSubtask> = emptyList(),
)

@Serializable
data class TaskSubtask(
    val uid: String,
    val taskUid: String,
    val content: String,
    val isCompleted: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: String,
    val updatedAt: String,
    val isDeleted: Boolean = false,
)

@Serializable
enum class TaskState {
    @SerialName("active") ACTIVE,
    @SerialName("completed") COMPLETED,
    @SerialName("archived") ARCHIVED,
    @SerialName("abandoned") ABANDONED,
    @SerialName("abandonedArchived") ABANDONED_ARCHIVED,
}

@Serializable
enum class TaskAction {
    MARK_COMPLETE,
    MARK_ARCHIVE,
    MARK_GIVE_UP,
    RESTORE_ACTIVE,
    UNARCHIVE,
}

object TaskStateMachine {
    fun apply(current: TaskState, action: TaskAction): TaskState? = when (current to action) {
        TaskState.ACTIVE to TaskAction.MARK_COMPLETE -> TaskState.COMPLETED
        TaskState.COMPLETED to TaskAction.MARK_ARCHIVE -> TaskState.ARCHIVED
        TaskState.ARCHIVED to TaskAction.UNARCHIVE -> TaskState.COMPLETED
        TaskState.ACTIVE to TaskAction.MARK_GIVE_UP -> TaskState.ABANDONED
        TaskState.ABANDONED to TaskAction.MARK_ARCHIVE -> TaskState.ABANDONED_ARCHIVED
        TaskState.ABANDONED_ARCHIVED to TaskAction.UNARCHIVE -> TaskState.ABANDONED
        TaskState.ACTIVE to TaskAction.RESTORE_ACTIVE,
        TaskState.COMPLETED to TaskAction.RESTORE_ACTIVE,
        TaskState.ABANDONED to TaskAction.RESTORE_ACTIVE -> TaskState.ACTIVE
        else -> null
    }

    fun isNoOp(state: TaskState, action: TaskAction): Boolean = when (action) {
        TaskAction.MARK_COMPLETE -> state == TaskState.COMPLETED
        TaskAction.MARK_GIVE_UP -> state == TaskState.ABANDONED
        TaskAction.RESTORE_ACTIVE -> state == TaskState.ACTIVE
        TaskAction.MARK_ARCHIVE -> state == TaskState.ARCHIVED || state == TaskState.ABANDONED_ARCHIVED
        TaskAction.UNARCHIVE -> state == TaskState.COMPLETED || state == TaskState.ABANDONED
    }

    fun isVisibleInMainList(state: TaskState): Boolean =
        state == TaskState.ACTIVE || state == TaskState.COMPLETED || state == TaskState.ABANDONED

    fun isVisibleInArchive(state: TaskState): Boolean =
        state == TaskState.ARCHIVED || state == TaskState.ABANDONED_ARCHIVED
}
