package com.deadliner.bridge

import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitTracking
import com.deadliner.domain.ai.AiHabitProposal
import com.deadliner.domain.ai.AiTaskProposal
import com.deadliner.domain.ai.model.CoreEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

private val bridgeJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
internal data class LifiBridgeConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val timezone: String,
)

internal data class LifiUtilityCache(
    val operation: String,
    val configPayload: String,
    val requestPayload: String,
    val response: String,
)

@Serializable
internal data class LifiMonthlyAnalysisRequest(
    val monthName: String,
    val metricsSummary: String,
    val completedTaskNames: List<String>,
)

@Serializable
private data class LifiEventEnvelope(
    val type: String,
    val requestId: String? = null,
    val stage: String? = null,
    val status: String? = null,
    val message: String? = null,
    val agentName: String? = null,
    val phase: String? = null,
    val id: String? = null,
    val toolName: String? = null,
    val argsJson: String? = null,
    val executionMode: String? = null,
    val primaryIntent: String? = null,
    val tasks: List<LifiTaskPayload> = emptyList(),
    val habits: List<LifiHabitPayload> = emptyList(),
    val chatResponse: String? = null,
    val sessionSummary: String? = null,
    val newMemories: List<String> = emptyList(),
    val userProfile: String? = null,
    val addedMemories: List<String> = emptyList(),
    val profileUpdated: Boolean? = null,
    val newRevision: Long? = null,
)

@Serializable
private data class LifiTaskPayload(val name: String, val dueTime: String? = null, val note: String? = null)

@Serializable
private data class LifiHabitPayload(
    val name: String,
    val schedule: HabitSchedule,
    val goal: HabitGoal,
    val tracking: HabitTracking,
    val reminder: HabitReminder? = null,
)

internal fun lifiEventJson(event: CoreEvent): String = bridgeJson.encodeToString(
    when (event) {
        is CoreEvent.OnLifecycle -> LifiEventEnvelope(
            type = "lifecycle", requestId = event.requestId, stage = event.stage,
            status = event.status, message = event.message,
        )
        is CoreEvent.OnThinking -> LifiEventEnvelope(
            type = "thinking", agentName = event.agentName, phase = event.phase, message = event.message,
        )
        is CoreEvent.OnTextStream -> LifiEventEnvelope(type = "textStream", message = event.chunk)
        is CoreEvent.OnToolRequest -> LifiEventEnvelope(
            type = "toolRequest", id = event.id, toolName = lifiToolName(event.tool),
            argsJson = lifiToolArgsJson(event.tool),
            executionMode = event.executionMode?.name?.lowercase(),
        )
        is CoreEvent.OnFinish -> LifiEventEnvelope(
            type = "finish", primaryIntent = event.result.primaryIntent,
            tasks = event.result.tasks.map(::lifiTaskPayload),
            habits = event.result.habits.map(::lifiHabitPayload),
            chatResponse = event.result.chatResponse, sessionSummary = event.result.sessionSummary,
            newMemories = event.result.newMemories, userProfile = event.result.userProfile,
        )
        is CoreEvent.OnMemoryCommitted -> LifiEventEnvelope(
            type = "memoryCommitted", addedMemories = event.addedMemories,
            profileUpdated = event.profileUpdated, newRevision = event.newRevision,
        )
        is CoreEvent.OnError -> LifiEventEnvelope(type = "error", message = com.deadliner.domain.ai.agent.AgentErrorMessages.describe(event.message))
    },
)

private fun lifiTaskPayload(proposal: AiTaskProposal) =
    LifiTaskPayload(name = proposal.title, dueTime = proposal.dueAt, note = proposal.note)

private fun lifiHabitPayload(proposal: AiHabitProposal) = LifiHabitPayload(
    name = proposal.name,
    schedule = proposal.schedule,
    goal = proposal.goal,
    tracking = proposal.tracking,
    reminder = proposal.reminder,
)

private fun lifiToolName(call: com.deadliner.domain.ai.model.ToolCall): String = when (call) {
    is com.deadliner.domain.ai.model.ToolCall.ReadTasks -> "read_tasks"
    is com.deadliner.domain.ai.model.ToolCall.CreateTask -> "create_task"
    is com.deadliner.domain.ai.model.ToolCall.UpdateDeadline -> "update_deadline"
    is com.deadliner.domain.ai.model.ToolCall.ReadHabits -> "read_habits"
    is com.deadliner.domain.ai.model.ToolCall.CreateHabit -> "create_habit"
}

private fun lifiToolArgsJson(call: com.deadliner.domain.ai.model.ToolCall): String = when (call) {
    is com.deadliner.domain.ai.model.ToolCall.ReadTasks -> bridgeJson.encodeToString(
        LifiReadTasksArguments(call.timeRangeDays, call.status, call.keywords, call.limit, call.sort),
    )
    is com.deadliner.domain.ai.model.ToolCall.CreateTask -> bridgeJson.encodeToString(
        LifiCreateTaskArguments(call.name, call.dueTime, call.note),
    )
    is com.deadliner.domain.ai.model.ToolCall.UpdateDeadline -> bridgeJson.encodeToString(
        LifiUpdateDeadlineArguments(call.taskId, call.newDueTime),
    )
    is com.deadliner.domain.ai.model.ToolCall.ReadHabits -> bridgeJson.encodeToString(
        LifiReadHabitsArguments(call.keywords),
    )
    is com.deadliner.domain.ai.model.ToolCall.CreateHabit -> bridgeJson.encodeToString(
        LifiCreateHabitArguments(call.name, call.schedule, call.goal, call.tracking, call.reminder),
    )
}

@Serializable private data class LifiReadTasksArguments(val timeRangeDays: Int?, val status: String?, val keywords: List<String>, val limit: Int, val sort: String?)
@Serializable private data class LifiCreateTaskArguments(val name: String, val dueTime: String?, val note: String?)
@Serializable private data class LifiUpdateDeadlineArguments(val taskId: String, val newDueTime: String)
@Serializable private data class LifiReadHabitsArguments(val keywords: List<String>)
@Serializable private data class LifiCreateHabitArguments(
    val name: String,
    val schedule: HabitSchedule,
    val goal: HabitGoal,
    val tracking: HabitTracking,
    val reminder: HabitReminder?,
)


@Serializable
internal data class LifiV2Command(
    val protocolVersion: Int = 2,
    val command: String,
    val commandId: String = "",
    val sessionId: String = "",
    val runId: String = "",
    val config: String? = null,
    val text: String? = null,
    val actionId: String? = null,
    val tool: String? = null,
    val payload: String? = null,
    val consumerId: String = "",
    val afterSeq: Long = 0,
    val batchId: String = "",
    val lastSeq: Long = 0,
)
@Serializable
internal data class LifiV2Reply(val sessionId: String? = null, val protocolVersion: Int = 2, val accepted: Boolean = false, val runId: String? = null, val afterSeq: Long = 0, val error: String? = null)

internal fun lifiV2SnapshotJson(snapshot: com.deadliner.domain.ai.agent.AgentV2Snapshot): String {
    val element = bridgeJson.encodeToJsonElement(com.deadliner.domain.ai.agent.AgentV2Snapshot.serializer(), snapshot).jsonObject.toMutableMap()
    snapshot.pending?.let { element["pending"] = bridgeJson.parseToJsonElement(lifiEventJson(it)) }
    snapshot.terminal?.let { element["terminal"] = lifiV2EventElement(it) }
    return kotlinx.serialization.json.JsonObject(element).toString()
}
private fun lifiV2EventElement(event: com.deadliner.domain.ai.agent.AgentV2Event): kotlinx.serialization.json.JsonObject {
    val element = bridgeJson.encodeToJsonElement(com.deadliner.domain.ai.agent.AgentV2Event.serializer(), event).jsonObject.toMutableMap()
    event.compatibilityEvent?.let { element["compatibilityEvent"] = bridgeJson.parseToJsonElement(lifiEventJson(it)) }
    return kotlinx.serialization.json.JsonObject(element)
}
internal fun lifiV2BatchJson(batch: com.deadliner.domain.ai.agent.AgentV2Batch): String {
    val element = bridgeJson.encodeToJsonElement(com.deadliner.domain.ai.agent.AgentV2Batch.serializer(), batch).jsonObject.toMutableMap()
    element["events"] = kotlinx.serialization.json.JsonArray(batch.events.map(::lifiV2EventElement))
    element["snapshot"] = bridgeJson.parseToJsonElement(lifiV2SnapshotJson(batch.snapshot))
    return kotlinx.serialization.json.JsonObject(element).toString()
}
