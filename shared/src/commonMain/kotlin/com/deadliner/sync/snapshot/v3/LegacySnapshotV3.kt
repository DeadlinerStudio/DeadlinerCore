package com.deadliner.sync.snapshot.v3

import com.deadliner.domain.habit.HabitAlgorithms
import com.deadliner.domain.habit.HabitWeekdays
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** JSON-level migration for snapshots emitted before Habit adopted schedule/goal/tracking. */
internal object LegacySnapshotV3Migrator {
    fun migrate(raw: String, json: Json): String? {
        val payload = json.parseToJsonElement(raw) as? JsonObject ?: return null
        val payloadVersion = payload.intOrNull("formatVersion")
        val snapshot = payload["snapshot"] as? JsonObject
        if (snapshot == null) {
            if (payloadVersion != LEGACY_FORMAT_VERSION) return null
            return JsonObject(payload + ("formatVersion" to JsonPrimitive(CURRENT_FORMAT_VERSION))).toString()
        }
        val habits = snapshot["habits"] as? JsonArray ?: JsonArray(emptyList())
        val snapshotVersion = snapshot.intOrNull("format_version")
        val hasLegacyHabit = habits.any(::isLegacyHabitItem)
        val isDeclaredV3 = payloadVersion == LEGACY_FORMAT_VERSION || snapshotVersion == LEGACY_FORMAT_VERSION

        if (!hasLegacyHabit && !isDeclaredV3) return null
        require(payloadVersion == null || payloadVersion in SUPPORTED_INPUT_VERSIONS) {
            "Unsupported sync format $payloadVersion"
        }
        require(snapshotVersion == null || snapshotVersion in SUPPORTED_INPUT_VERSIONS) {
            "Unsupported snapshot format $snapshotVersion"
        }

        val migratedSnapshot = JsonObject(
            snapshot + mapOf(
                "format_version" to JsonPrimitive(CURRENT_FORMAT_VERSION),
                "habits" to JsonArray(habits.map(::migrateHabitItem)),
            ),
        )
        return JsonObject(
            payload + mapOf(
                "formatVersion" to JsonPrimitive(CURRENT_FORMAT_VERSION),
                "snapshot" to migratedSnapshot,
            ),
        ).toString()
    }

    private fun isLegacyHabitItem(element: JsonElement): Boolean {
        val habit = (element as? JsonObject)
            ?.get("doc")
            ?.let { it as? JsonObject }
            ?.get("habit")
            ?.let { it as? JsonObject }
            ?: return false
        return "period" in habit && ("schedule" !in habit || "goal" !in habit)
    }

    private fun migrateHabitItem(element: JsonElement): JsonElement {
        val item = element as? JsonObject ?: return element
        val doc = item["doc"] as? JsonObject ?: return element
        val habit = doc["habit"] as? JsonObject ?: return element
        if (!isLegacyHabitItem(item)) return element

        val period = habit.string("period").uppercase()
        require(period in LEGACY_PERIODS) { "Unsupported legacy habit period" }
        val goalType = habit.stringOrNull("goalType", "goal_type")?.uppercase() ?: "PER_PERIOD"
        require(goalType == "PER_PERIOD" || goalType == "TOTAL") { "Unsupported legacy habit goal type" }
        val timesPerPeriod = habit.intOrNull("timesPerPeriod", "times_per_period") ?: 1
        val records = doc["records"] as? JsonArray ?: JsonArray(emptyList())
        val targetCount = when {
            period == "EBBINGHAUS" -> 1
            goalType == "TOTAL" -> habit.intOrNull("totalTarget", "total_target")
                ?: completedRecordTotal(records)
                ?: timesPerPeriod
            else -> timesPerPeriod
        }.coerceAtLeast(1)
        val startOn = habit.stringOrNull("createdAt", "created_at").validDatePrefix()
            ?: (item["ver"] as? JsonObject)?.stringOrNull("ts").validDatePrefix()
            ?: FALLBACK_START_DATE

        val schedule = when (period) {
            "ONCE" -> JsonObject(
                mapOf(
                    "kind" to JsonPrimitive("ONCE"),
                    "startOn" to JsonPrimitive(startOn),
                ),
            )
            "EBBINGHAUS" -> JsonObject(
                mapOf(
                    "kind" to JsonPrimitive("ALGORITHM"),
                    "startOn" to JsonPrimitive(startOn),
                    "algorithmKey" to JsonPrimitive(HabitAlgorithms.EBBINGHAUS_CLASSIC_V1),
                ),
            )
            else -> JsonObject(
                mapOf(
                    "kind" to JsonPrimitive("WEEKLY_DAYS"),
                    "startOn" to JsonPrimitive(startOn),
                    "weekdaysMask" to JsonPrimitive(HabitWeekdays.ALL),
                ),
            )
        }
        val goalWindow = when {
            goalType == "TOTAL" -> "LIFETIME"
            period == "WEEKLY" -> "WEEK"
            period == "MONTHLY" -> "MONTH"
            else -> "SCHEDULED_DAY"
        }
        val goal = JsonObject(
            mapOf(
                "window" to JsonPrimitive(goalWindow),
                "targetCount" to JsonPrimitive(targetCount),
            ),
        )
        val tracking = JsonObject(
            mapOf(
                "mode" to JsonPrimitive(
                    if (period == "EBBINGHAUS" || targetCount == 1) "CHECK_OFF" else "QUANTITY",
                ),
                "defaultIncrement" to JsonPrimitive(1),
            ),
        )
        val migratedHabit = JsonObject(
            habit.filterKeys { it !in LEGACY_HABIT_FIELDS } + mapOf(
                "schedule" to schedule,
                "goal" to goal,
                "tracking" to tracking,
            ),
        )
        return JsonObject(item + ("doc" to JsonObject(doc + ("habit" to migratedHabit))))
    }

    private fun completedRecordTotal(records: JsonArray): Int? {
        val completed = records.mapNotNull { it as? JsonObject }.filter { record ->
            record.stringOrNull("status") == "COMPLETED" &&
                record.booleanOrNull("isDeleted", "is_deleted") != true
        }
        if (completed.isEmpty()) return null
        return completed.sumOf { it.intOrNull("count") ?: 1 }
    }

    private fun JsonObject.string(name: String): String =
        stringOrNull(name) ?: throw IllegalArgumentException("Missing legacy habit field '$name'")

    private fun JsonObject.stringOrNull(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        get(name)?.jsonPrimitive?.content
    }

    private fun JsonObject.intOrNull(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
        get(name)?.jsonPrimitive?.intOrNull
    }

    private fun JsonObject.booleanOrNull(vararg names: String): Boolean? = names.firstNotNullOfOrNull { name ->
        get(name)?.jsonPrimitive?.booleanOrNull
    }

    private fun String?.validDatePrefix(): String? = this?.take(10)?.takeIf { candidate ->
        runCatching { LocalDate.parse(candidate) }.isSuccess
    }

    private val LEGACY_PERIODS = setOf("DAILY", "WEEKLY", "MONTHLY", "ONCE", "EBBINGHAUS")
    private val LEGACY_HABIT_FIELDS = setOf(
        "period",
        "timesPerPeriod",
        "times_per_period",
        "goalType",
        "goal_type",
        "totalTarget",
        "total_target",
    )
    private val SUPPORTED_INPUT_VERSIONS = setOf(LEGACY_FORMAT_VERSION, CURRENT_FORMAT_VERSION)
    private const val LEGACY_FORMAT_VERSION = 3
    private const val CURRENT_FORMAT_VERSION = 4
    private const val FALLBACK_START_DATE = "1970-01-01"
}
