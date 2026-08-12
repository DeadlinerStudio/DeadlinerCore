package com.deadliner.service

import com.deadliner.db.repository.HabitRepository
import com.deadliner.db.repository.TaskRepository
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitGoalType
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.platform.PlatformIdentity
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Executes Lifi's persisted-data tools under the same KMP business authority as the UI ViewModels. */
class LifiToolExecutor(
    private val tasks: TaskRepository,
    private val habits: HabitRepository,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val notificationOrchestrator: NotificationOrchestrator? = null,
) {
    fun execute(toolName: String, argumentsJson: String): String = runCatching {
        val arguments = parseArguments(argumentsJson)
        when (toolName) {
            READ_TASKS -> readTasks(arguments)
            CREATE_TASK -> createTasks(arguments)
            UPDATE_DEADLINE -> updateDeadline(arguments)
            READ_HABITS -> readHabits(arguments)
            CREATE_HABIT -> createHabits(arguments)
            else -> failure("UNSUPPORTED_TOOL", "unsupported tool: $toolName")
        }
    }.getOrElse { error -> failure("TOOL_EXECUTION_FAILED", error.message ?: "tool execution failed") }.toString()

    private fun readTasks(args: JsonObject): JsonObject {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val rangeDays = (args.int("timeRangeDays") ?: 7).coerceIn(0, 365)
        val dueEnd = LocalDateTime(now.date.plus(rangeDays, DateTimeUnit.DAY), now.time)
        val status = args.string("status")?.uppercase()?.takeIf { it in TASK_STATUSES } ?: "OPEN"
        val keywords = args.stringList("keywords").map(String::trim).filter(String::isNotEmpty).take(3)
        val limit = (args.int("limit") ?: 20).coerceIn(1, 50)
        val sort = args.string("sort")?.uppercase()?.takeIf { it in TASK_SORTS } ?: "DUE_ASC"

        val selected = tasks.list().asSequence()
            .filterNot(Task::isDeleted)
            .filter { it.state !in ARCHIVED_TASK_STATES }
            .filter { task ->
                when (status) {
                    "DONE" -> task.state == TaskState.COMPLETED
                    "ALL" -> true
                    else -> task.state == TaskState.ACTIVE || task.state == TaskState.ABANDONED
                }
            }
            .filter { task ->
                keywords.isEmpty() || keywords.any { keyword ->
                    task.title.contains(keyword, ignoreCase = true) || task.note.contains(keyword, ignoreCase = true)
                }
            }
            .map { it to parseLocalDateTime(it.dueAt) }
            .filter { (_, due) -> rangeDays == 0 || due == null || due <= dueEnd }
            .let { sequence ->
                if (sort == "UPDATED_DESC") sequence.sortedByDescending { it.first.updatedAt }
                else sequence.sortedBy { it.second ?: LocalDateTime(9999, 12, 31, 23, 59) }
            }
            .take(limit)
            .toList()

        val taskItems = buildJsonArray {
            selected.forEach { (task, due) ->
                add(buildJsonObject {
                    put("id", task.uid)
                    put("name", task.title)
                    put("due", due?.toDisplayString().orEmpty())
                    put("status", if (task.state == TaskState.COMPLETED) "DONE" else "OPEN")
                    put("notePreview", task.note.trim().replace("\n", " ").take(40))
                })
            }
        }
        val overdue = selected.count { (task, due) -> due != null && due < now && task.state != TaskState.COMPLETED }
        val dueSoonEnd = Clock.System.now().plus(24, DateTimeUnit.HOUR).toLocalDateTime(TimeZone.currentSystemDefault())
        val dueSoon = selected.count { (task, due) ->
            due != null && due >= now && due < dueSoonEnd && task.state != TaskState.COMPLETED
        }
        return buildJsonObject {
            put("tasks", taskItems)
            put("summary", buildJsonObject {
                put("count", selected.size)
                put("overdue", overdue)
                put("dueSoon24h", dueSoon)
            })
        }
    }

    private fun createTasks(args: JsonObject): JsonObject = executeBatch(args, "tasks", "task") { item ->
        val name = item.string("name")?.trim().orEmpty()
        val due = parseLocalDateTime(item.string("dueTime"))
        require(name.isNotEmpty()) { "create_task.name is required" }
        require(due != null) { "create_task.dueTime format is invalid" }
        val now = Clock.System.now().toString()
        val task = Task(
            uid = PlatformIdentity.newId(),
            title = name,
            note = item.string("note").orEmpty(),
            startAt = now,
            dueAt = due.toString(),
            createdAt = now,
            updatedAt = now,
        )
        tasks.create(task)
        notificationOrchestrator?.onTaskCreated(task)
        buildJsonObject {
            put("id", task.uid)
            put("name", task.title)
            put("dueTime", due.toDisplayString())
            put("note", task.note)
        }
    }

    private fun updateDeadline(args: JsonObject): JsonObject {
        val uid = args.string("taskId")?.trim().orEmpty()
        val due = parseLocalDateTime(args.string("newDueTime"))
        if (uid.isEmpty()) return failure("INVALID_ARGUMENT", "update_deadline.taskId is required")
        if (due == null) return failure("INVALID_ARGUMENT", "update_deadline.newDueTime format is invalid")
        val existing = tasks.find(uid) ?: return failure("NOT_FOUND", "task not found")
        val updated = existing.copy(dueAt = due.toString(), updatedAt = Clock.System.now().toString())
        tasks.update(updated)
        notificationOrchestrator?.onTaskUpdated(updated)
        return buildJsonObject {
            put("ok", true)
            put("task", buildJsonObject {
                put("id", updated.uid)
                put("name", updated.title)
                put("dueTime", due.toDisplayString())
            })
        }
    }

    private fun readHabits(args: JsonObject): JsonObject {
        val keywords = args.stringList("keywords").map(String::trim).filter(String::isNotEmpty).take(3)
        val selected = habits.list().asSequence()
            .filterNot(Habit::isDeleted)
            .filter { it.status == HabitStatus.ACTIVE }
            .filter { habit -> keywords.isEmpty() || keywords.any { habit.name.contains(it, ignoreCase = true) } }
            .take(50)
            .toList()
        return buildJsonObject {
            put("habits", buildJsonArray {
                selected.forEach { habit ->
                    add(buildJsonObject {
                        put("id", habit.uid)
                        put("name", habit.name)
                        put("period", habit.period.name)
                        put("timesPerPeriod", habit.timesPerPeriod)
                        put("goalType", habit.goalType.name)
                        habit.totalTarget?.let { put("totalTarget", it) }
                    })
                }
            })
            put("summary", buildJsonObject { put("count", selected.size) })
        }
    }

    private fun createHabits(args: JsonObject): JsonObject = executeBatch(args, "habits", "habit") { item ->
        val name = item.string("name")?.trim().orEmpty()
        val period = item.string("period")?.let { value -> enumValues<HabitPeriod>().firstOrNull { it.name == value.uppercase() } }
        val goalType = item.string("goalType")?.let { value -> enumValues<HabitGoalType>().firstOrNull { it.name == value.uppercase() } }
        val timesPerPeriod = item.int("timesPerPeriod") ?: 1
        val totalTarget = item.int("totalTarget")
        require(name.isNotEmpty()) { "create_habit.name is required" }
        require(period != null) { "create_habit.period is invalid" }
        require(goalType != null) { "create_habit.goalType is invalid" }
        require(timesPerPeriod > 0) { "create_habit.timesPerPeriod must be > 0" }
        require(goalType != HabitGoalType.TOTAL || (totalTarget != null && totalTarget > 0)) {
            "create_habit.totalTarget is required when goalType is TOTAL"
        }
        val now = Clock.System.now().toString()
        val habit = Habit(
            uid = PlatformIdentity.newId(),
            name = name,
            period = period,
            timesPerPeriod = timesPerPeriod,
            goalType = goalType,
            totalTarget = totalTarget,
            createdAt = now,
        )
        habits.create(habit)
        notificationOrchestrator?.onHabitCreated(habit)
        buildJsonObject {
            put("id", habit.uid)
            put("name", habit.name)
            put("period", habit.period.name)
            put("timesPerPeriod", habit.timesPerPeriod)
            put("goalType", habit.goalType.name)
            habit.totalTarget?.let { put("totalTarget", it) }
        }
    }

    private fun executeBatch(
        args: JsonObject,
        batchField: String,
        resultField: String,
        executeOne: (JsonObject) -> JsonObject,
    ): JsonObject {
        val isBatch = args[batchField] is JsonArray
        val items = if (isBatch) args[batchField]!!.jsonArray else JsonArray(listOf(args))
        if (items.isEmpty()) return failure("INVALID_ARGUMENT", "$batchField cannot be empty")
        val results = items.mapIndexed { index, element ->
            runCatching { executeOne(element.jsonObject) }
                .fold(
                    onSuccess = { value -> buildJsonObject { put("index", index); put("ok", true); put(resultField, value) } },
                    onFailure = { error -> buildJsonObject {
                        put("index", index)
                        put("ok", false)
                        put("errorCode", "INVALID_ARGUMENT")
                        put("message", error.message ?: "invalid arguments")
                    } },
                )
        }
        val successes = results.filter { it["ok"]?.jsonPrimitive?.content == "true" }
        val failureCount = results.size - successes.size
        return buildJsonObject {
            put("ok", failureCount == 0)
            put("partialSuccess", successes.isNotEmpty() && failureCount > 0)
            successes.firstOrNull()?.get(resultField)?.let { put(resultField, it) }
            put(if (resultField == "task") "createdTasks" else "createdHabits", buildJsonArray {
                successes.forEach { result -> result[resultField]?.let(::add) }
            })
            put("items", JsonArray(results))
            put("summary", buildJsonObject {
                put("total", results.size)
                put("successCount", successes.size)
                put("failureCount", failureCount)
            })
        }
    }

    private fun parseArguments(raw: String): JsonObject {
        val root = json.parseToJsonElement(raw).jsonObject
        return (root["args"] as? JsonObject) ?: root
    }

    private fun failure(code: String, message: String) = buildJsonObject {
        put("ok", false)
        put("errorCode", code)
        put("message", message)
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    private fun parseLocalDateTime(raw: String?): LocalDateTime? = raw?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
        runCatching { LocalDateTime.parse(value.replace(' ', 'T')) }.getOrNull()
    }

    private fun LocalDateTime.toDisplayString(): String = toString().replace('T', ' ').take(16)

    private companion object {
        const val READ_TASKS = "read_tasks"
        const val CREATE_TASK = "create_task"
        const val UPDATE_DEADLINE = "update_deadline"
        const val READ_HABITS = "read_habits"
        const val CREATE_HABIT = "create_habit"
        val TASK_STATUSES = setOf("OPEN", "DONE", "ALL")
        val TASK_SORTS = setOf("DUE_ASC", "UPDATED_DESC")
        val ARCHIVED_TASK_STATES = setOf(TaskState.ARCHIVED, TaskState.ABANDONED_ARCHIVED)
    }
}
