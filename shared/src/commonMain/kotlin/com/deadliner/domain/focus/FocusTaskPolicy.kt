package com.deadliner.domain.focus

import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.task.TaskState
import com.deadliner.domain.task.TaskStateMachine

/** Pure guard shared by every Task mutation entry. */
object FocusTaskPolicy {
    fun requiresResolution(
        task: Task,
        action: TaskAction,
        activeTimer: ActiveFocusTimer?,
    ): Boolean {
        if (activeTimer?.taskUid != task.uid) return false
        val targetState = TaskStateMachine.apply(task.state, action) ?: return false
        return targetState != TaskState.ACTIVE
    }

    fun requiresResolution(
        current: Task,
        replacement: Task,
        activeTimer: ActiveFocusTimer?,
    ): Boolean = activeTimer?.taskUid == current.uid &&
        (
            replacement.isDeleted && !current.isDeleted ||
                replacement.state != current.state && replacement.state != TaskState.ACTIVE
            )

    fun requiresResolutionForDelete(taskUid: String, activeTimer: ActiveFocusTimer?): Boolean =
        activeTimer?.taskUid == taskUid
}
