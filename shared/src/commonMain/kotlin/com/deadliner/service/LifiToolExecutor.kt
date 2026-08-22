package com.deadliner.service

import com.deadliner.db.repository.HabitRepository
import com.deadliner.db.repository.TaskRepository
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitAlgorithms
import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitGoalWindow
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.habit.HabitTracking
import com.deadliner.domain.habit.HabitTrackingMode
import com.deadliner.domain.habit.HabitWeekdays
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.platform.PlatformIdentity
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
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
                        put("schedule", habit.scheduleJson())
                        put("goal", habit.goalJson())
                        put("tracking", habit.trackingJson())
                        habit.reminder?.let { put("reminder", it.reminderJson()) }
                    })
                }
            })
            put("summary", buildJsonObject { put("count", selected.size) })
        }
    }

    private fun createHabits(args: JsonObject): JsonObject = executeBatch(args, "habits", "habit") { item ->
        val name = item.string("name")?.trim().orEmpty()
        require(name.isNotEmpty()) { "create_habit.name is required" }
        val now = Clock.System.now().toString()
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        val normalized = item.toHabitInput(today)
        val habit = Habit(
            uid = PlatformIdentity.newId(),
            name = name,
            schedule = normalized.schedule,
            goal = normalized.goal,
            tracking = normalized.tracking,
            reminder = normalized.reminder,
            createdAt = now,
        )
        habits.create(habit)
        notificationOrchestrator?.onHabitCreated(habit)
        buildJsonObject {
            put("id", habit.uid)
            put("name", habit.name)
            put("schedule", habit.scheduleJson())
            put("goal", habit.goalJson())
            put("tracking", habit.trackingJson())
            habit.reminder?.let { put("reminder", it.reminderJson()) }
            normalized.compatibilityWarning?.let { put("compatibilityWarning", it) }
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
    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    private fun parseLocalDateTime(raw: String?): LocalDateTime? = raw?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
        runCatching { LocalDateTime.parse(value.replace(' ', 'T')) }.getOrNull()
    }

    private fun LocalDateTime.toDisplayString(): String = toString().replace('T', ' ').take(16)

    private fun JsonObject.toHabitInput(today: String): NormalizedHabitInput {
        val scheduleObject = this["schedule"] as? JsonObject
        if (scheduleObject == null) return legacyHabitInput(today)
        val goalObject = this["goal"] as? JsonObject
        val trackingObject = this["tracking"] as? JsonObject
        require(goalObject != null) { "create_habit.goal is required" }
        require(trackingObject != null) { "create_habit.tracking is required" }

        val kind = scheduleObject.enum<HabitScheduleKind>("kind")
            ?: throw IllegalArgumentException("create_habit.schedule.kind is invalid")
        val startOn = scheduleObject.string("startOn")
            ?: throw IllegalArgumentException("create_habit.schedule.startOn is required")
        require(runCatching { LocalDate.parse(startOn) }.isSuccess) { "create_habit.schedule.startOn is invalid" }
        val endOn = scheduleObject.string("endOn")
        require(endOn == null || runCatching { LocalDate.parse(endOn) }.isSuccess) { "create_habit.schedule.endOn is invalid" }
        val schedule = HabitSchedule(
            kind = kind,
            startOn = startOn,
            endOn = endOn,
            weekdaysMask = scheduleObject.int("weekdaysMask"),
            algorithmKey = scheduleObject.string("algorithmKey"),
        )
        val goal = HabitGoal(
            window = goalObject.enum<HabitGoalWindow>("window")
                ?: throw IllegalArgumentException("create_habit.goal.window is invalid"),
            targetCount = goalObject.int("targetCount")
                ?: throw IllegalArgumentException("create_habit.goal.targetCount is required"),
        )
        val tracking = HabitTracking(
            mode = trackingObject.enum<HabitTrackingMode>("mode")
                ?: throw IllegalArgumentException("create_habit.tracking.mode is invalid"),
            unit = trackingObject.string("unit")?.trim()?.ifBlank { null },
            defaultIncrement = trackingObject.int("defaultIncrement") ?: 1,
        )
        val reminder = (this["reminder"] as? JsonObject)?.let { value ->
            val localTime = value.string("localTime")
                ?: throw IllegalArgumentException("create_habit.reminder.localTime is required")
            require(REMINDER_TIME.matches(localTime)) { "create_habit.reminder.localTime is invalid" }
            HabitReminder(localTime, value.boolean("isEnabled") ?: true)
        }
        return NormalizedHabitInput(schedule, goal, tracking, reminder)
    }

    private fun JsonObject.legacyHabitInput(today: String): NormalizedHabitInput {
        val period = string("period")?.uppercase()
        require(period in LEGACY_PERIODS) { "create_habit.period is invalid" }
        val goalType = string("goalType")?.uppercase()
        require(goalType == "PER_PERIOD" || goalType == "TOTAL") { "create_habit.goalType is invalid" }
        val times = int("timesPerPeriod") ?: 1
        require(times > 0) { "create_habit.timesPerPeriod must be > 0" }
        val totalTarget = int("totalTarget")
        require(goalType != "TOTAL" || (totalTarget != null && totalTarget > 0)) {
            "create_habit.totalTarget is required when goalType is TOTAL"
        }
        val schedule = when (period) {
            "ONCE" -> HabitSchedule(HabitScheduleKind.ONCE, today)
            "EBBINGHAUS" -> HabitSchedule(
                HabitScheduleKind.ALGORITHM,
                today,
                algorithmKey = HabitAlgorithms.EBBINGHAUS_CLASSIC_V1,
            )
            else -> HabitSchedule(HabitScheduleKind.WEEKLY_DAYS, today, weekdaysMask = HabitWeekdays.ALL)
        }
        val target = if (goalType == "TOTAL") totalTarget!! else times
        val window = when {
            goalType == "TOTAL" -> HabitGoalWindow.LIFETIME
            period == "WEEKLY" -> HabitGoalWindow.WEEK
            period == "MONTHLY" -> HabitGoalWindow.MONTH
            else -> HabitGoalWindow.SCHEDULED_DAY
        }
        return NormalizedHabitInput(
            schedule = schedule,
            goal = HabitGoal(window, target),
            tracking = HabitTracking(if (target == 1) HabitTrackingMode.CHECK_OFF else HabitTrackingMode.QUANTITY),
            reminder = null,
            compatibilityWarning = LEGACY_HABIT_WARNING,
        )
    }

    private inline fun <reified T : Enum<T>> JsonObject.enum(key: String): T? =
        string(key)?.uppercase()?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

    private fun Habit.scheduleJson() = buildJsonObject {
        put("kind", schedule.kind.name)
        put("startOn", schedule.startOn)
        schedule.endOn?.let { put("endOn", it) }
        schedule.weekdaysMask?.let { put("weekdaysMask", it) }
        schedule.algorithmKey?.let { put("algorithmKey", it) }
    }

    private fun Habit.goalJson() = buildJsonObject {
        put("window", goal.window.name)
        put("targetCount", goal.targetCount)
    }

    private fun Habit.trackingJson() = buildJsonObject {
        put("mode", tracking.mode.name)
        tracking.unit?.let { put("unit", it) }
        put("defaultIncrement", tracking.defaultIncrement)
    }

    private fun HabitReminder.reminderJson() = buildJsonObject {
        put("localTime", localTime)
        put("isEnabled", isEnabled)
    }

    private data class NormalizedHabitInput(
        val schedule: HabitSchedule,
        val goal: HabitGoal,
        val tracking: HabitTracking,
        val reminder: HabitReminder?,
        val compatibilityWarning: String? = null,
    )

    private companion object {
        const val READ_TASKS = "read_tasks"
        const val CREATE_TASK = "create_task"
        const val UPDATE_DEADLINE = "update_deadline"
        const val READ_HABITS = "read_habits"
        const val CREATE_HABIT = "create_habit"
        val TASK_STATUSES = setOf("OPEN", "DONE", "ALL")
        val TASK_SORTS = setOf("DUE_ASC", "UPDATED_DESC")
        val ARCHIVED_TASK_STATES = setOf(TaskState.ARCHIVED, TaskState.ABANDONED_ARCHIVED)
        val LEGACY_PERIODS = setOf("DAILY", "WEEKLY", "MONTHLY", "ONCE", "EBBINGHAUS")
        val REMINDER_TIME = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
        const val LEGACY_HABIT_WARNING = "Legacy habit fields were converted to schedule/goal/tracking; update the caller to the v2 contract."
    }
}
