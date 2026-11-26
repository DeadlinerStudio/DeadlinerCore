package com.deadliner.bridge

import com.deadliner.db.repository.TaskRepository
import com.deadliner.domain.task.TaskDeadlineDelay
import com.deadliner.domain.task.TaskDeadlineShift
import com.deadliner.domain.task.TaskDeadlineUnit
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** JSON boundary only; rules and persistence remain in the existing deadline commands. */
internal class TaskDeadlineJsonBridge(private val tasks: TaskRepository) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun execute(payload: String): String {
        val request = json.decodeFromString<DeadlineRequest>(payload)
        require(request.action == "PREVIEW" || request.action == "POSTPONE") { "Unknown deadline action" }
        val unit = TaskDeadlineUnit.entries.firstOrNull { it.name == request.unit }
            ?: return json.encodeToString(DeadlineResponse("INVALID_DELAY"))
        val delay = TaskDeadlineDelay(request.amount, unit)
        if (!delay.isValid) return json.encodeToString(DeadlineResponse("INVALID_DELAY", maximum = delay.maximum))
        val response = if (request.action == "PREVIEW") {
            val task = tasks.find(request.taskUid)
            val before = task?.dueAt
            val after = TaskDeadlineShift.postponed(before, delay)
            val outcome = when {
                task == null || task.isDeleted -> "NOT_FOUND"
                after == null -> "INVALID_DEADLINE"
                else -> "PREVIEW"
            }
            DeadlineResponse(outcome, before, after, delay.maximum)
        } else {
            val result = tasks.postpone(request.taskUid, delay, Clock.System.now().toString())
            DeadlineResponse(result.outcome.name, afterDeadline = result.task?.dueAt, maximum = delay.maximum)
        }
        return json.encodeToString(response)
    }

    // Existing domain values are not serializable; these types describe only the C ABI envelope.
    @Serializable private data class DeadlineRequest(
        val action: String,
        val taskUid: String,
        val amount: Int,
        val unit: String,
    )
    @Serializable private data class DeadlineResponse(
        val outcome: String,
        val beforeDeadline: String? = null,
        val afterDeadline: String? = null,
        val maximum: Int = 0,
    )
}
