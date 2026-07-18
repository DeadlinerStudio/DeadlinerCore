package com.deadliner.sync.snapshot.v2

import com.deadliner.domain.category.Category
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitGoalType
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitRecordStatus
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.sync.snapshot.SnapshotV3CategoryItem
import com.deadliner.sync.snapshot.SnapshotV3EntityVersion
import com.deadliner.sync.snapshot.SnapshotV3HabitDocument
import com.deadliner.sync.snapshot.SnapshotV3HabitItem
import com.deadliner.sync.snapshot.SnapshotV3Root
import com.deadliner.sync.snapshot.SnapshotV3TaskItem
import com.deadliner.sync.snapshot.SnapshotV3Version
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SnapshotV2MigrationInput(
    val tasks: LegacyTaskSnapshotV2Root? = null,
    val habits: LegacyHabitSnapshotV2Root? = null,
    val categories: LegacyCategorySnapshotV2Root? = null,
)

enum class SnapshotV2MigrationIssueCode {
    LIVE_ITEM_WITHOUT_DOCUMENT,
    UNSUPPORTED_TASK_TYPE,
    UNKNOWN_TASK_STATE,
    EMPTY_SUBTASK_UID,
    LEGACY_HABIT_CARRIER_FALLBACK,
    LEGACY_HABIT_CARRIER_SKIPPED,
    UNPARSEABLE_LEGACY_HABIT_METADATA,
    UNKNOWN_HABIT_PERIOD,
    UNKNOWN_HABIT_GOAL_TYPE,
    UNKNOWN_HABIT_STATUS,
    UNKNOWN_HABIT_RECORD_STATUS,
    AMBIGUOUS_TOMBSTONE_ASSUMED_TASK,
    DUPLICATE_UID_KEPT_NEWEST,
}

data class SnapshotV2MigrationIssue(
    val code: SnapshotV2MigrationIssueCode,
    val uid: String,
    val message: String,
)

data class SnapshotV2MigrationResult(
    val snapshot: SnapshotV3Root,
    val issues: List<SnapshotV2MigrationIssue>,
)

/**
 * Projects iOS v2 snapshots into the unified v3 contract. It is deterministic and side-effect
 * free: callers inspect [SnapshotV2MigrationResult.issues] before applying the snapshot locally.
 */
object SnapshotV2Migrator {
    private val legacyHabitJson = Json { ignoreUnknownKeys = true }

    fun migrate(input: SnapshotV2MigrationInput): SnapshotV2MigrationResult {
        require(input.tasks != null || input.habits != null || input.categories != null) {
            "At least one v2 snapshot is required"
        }
        val issues = mutableListOf<SnapshotV2MigrationIssue>()
        val categories = linkedMapOf<String, SnapshotV3CategoryItem>()
        val tasks = linkedMapOf<String, SnapshotV3TaskItem>()
        val habits = linkedMapOf<String, SnapshotV3HabitItem>()

        input.categories?.items.orEmpty().forEach { item ->
            val migrated = if (item.deleted) {
                SnapshotV3CategoryItem(item.uid, item.ver.toV3(), deleted = true)
            } else {
                val doc = item.doc
                if (doc == null) {
                    issues.missingDocument(item.uid)
                    return@forEach
                }
                SnapshotV3CategoryItem(
                    uid = item.uid,
                    ver = item.ver.toV3(),
                    deleted = false,
                    doc = Category(
                        uid = item.uid,
                        name = doc.name,
                        iconKey = doc.iconKey,
                        colorHex = doc.colorHex,
                        isPreset = doc.isPreset != 0,
                        sortOrder = doc.sortOrder,
                        createdAt = doc.createdAt,
                        updatedAt = doc.updatedAt,
                    ),
                )
            }
            categories.keepNewest(item.uid, migrated, { it.ver }, issues)
        }

        input.habits?.items.orEmpty().forEach { item ->
            val migrated = item.toV3Habit(issues) ?: return@forEach
            habits.keepNewest(item.uid, migrated, { it.ver }, issues)
        }

        val dedicatedHabitUids = input.habits?.items.orEmpty().mapTo(hashSetOf()) { it.uid }
        input.tasks?.items.orEmpty().forEach { item ->
            if (item.deleted) {
                if (item.uid in dedicatedHabitUids) return@forEach
                tasks.keepNewest(
                    item.uid,
                    SnapshotV3TaskItem(item.uid, item.ver.toV3(), deleted = true),
                    { it.ver },
                    issues,
                )
                issues += SnapshotV2MigrationIssue(
                    SnapshotV2MigrationIssueCode.AMBIGUOUS_TOMBSTONE_ASSUMED_TASK,
                    item.uid,
                    "A task v2 tombstone has no type; it was projected as a task tombstone.",
                )
                return@forEach
            }

            val doc = item.doc
            if (doc == null) {
                issues.missingDocument(item.uid)
                return@forEach
            }
            when (doc.type.lowercase()) {
                "task" -> tasks.keepNewest(item.uid, item.toV3Task(doc, issues), { it.ver }, issues)
                "habit" -> {
                    if (item.uid in dedicatedHabitUids) {
                        issues += SnapshotV2MigrationIssue(
                            SnapshotV2MigrationIssueCode.LEGACY_HABIT_CARRIER_SKIPPED,
                            item.uid,
                            "The dedicated habit v2 file is authoritative; its legacy DDL carrier was skipped.",
                        )
                    } else {
                        val fallback = item.toV3HabitFromCarrier(doc, issues)
                        habits.keepNewest(item.uid, fallback, { it.ver }, issues)
                    }
                }
                else -> issues += SnapshotV2MigrationIssue(
                    SnapshotV2MigrationIssueCode.UNSUPPORTED_TASK_TYPE,
                    item.uid,
                    "Unsupported v2 task item type '${doc.type}'.",
                )
            }
        }

        val rootVersion = listOfNotNull(input.tasks?.version, input.habits?.version, input.categories?.version)
            .maxWith(compareBy<LegacySnapshotV2Version> { it.ts }.thenBy { it.dev })

        return SnapshotV2MigrationResult(
            snapshot = SnapshotV3Root(
                version = SnapshotV3Version(ts = rootVersion.ts, dev = rootVersion.dev),
                categories = categories.values.toList(),
                tasks = tasks.values.toList(),
                habits = habits.values.toList(),
            ),
            issues = issues,
        )
    }

    private fun LegacyTaskSnapshotV2Item.toV3Task(
        doc: LegacyTaskSnapshotV2Doc,
        issues: MutableList<SnapshotV2MigrationIssue>,
    ): SnapshotV3TaskItem {
        val createdAt = doc.timestamp.ifBlank { ver.ts }
        val updatedAt = ver.ts.ifBlank { createdAt }
        val state = doc.state.toTaskState(uid, issues)
        return SnapshotV3TaskItem(
            uid = uid,
            ver = ver.toV3(),
            deleted = false,
            doc = Task(
                uid = uid,
                title = doc.name,
                note = doc.note,
                startAt = doc.startTime.blankToNull(),
                dueAt = doc.endTime.blankToNull(),
                state = state,
                completedAt = doc.completeTime.blankToNull(),
                categoryUid = doc.categoryUid?.blankToNull(),
                isStarred = doc.isStared != 0,
                calendarEventId = doc.calendarEvent.takeIf { it > 0 },
                createdAt = createdAt,
                updatedAt = updatedAt,
                subtasks = doc.subtasks.mapIndexed { index, subtask ->
                    val subtaskUid = subtask.id.ifBlank {
                        issues += SnapshotV2MigrationIssue(
                            SnapshotV2MigrationIssueCode.EMPTY_SUBTASK_UID,
                            uid,
                            "Subtask at index $index had no id; a deterministic uid was generated.",
                        )
                        "$uid:subtask:$index"
                    }
                    TaskSubtask(
                        uid = subtaskUid,
                        taskUid = uid,
                        content = subtask.content,
                        isCompleted = subtask.isCompleted != 0,
                        sortOrder = subtask.sortOrder,
                        createdAt = subtask.createdAt?.ifBlank { createdAt } ?: createdAt,
                        updatedAt = subtask.updatedAt?.ifBlank { updatedAt } ?: updatedAt,
                    )
                },
            ),
        )
    }

    private fun LegacyHabitSnapshotV2Item.toV3Habit(
        issues: MutableList<SnapshotV2MigrationIssue>,
    ): SnapshotV3HabitItem? {
        if (deleted) return SnapshotV3HabitItem(uid, ver.toV3(), deleted = true)
        val document = doc
        if (document == null) {
            issues.missingDocument(uid)
            return null
        }
        if (document.ddlUid != uid) {
            issues += SnapshotV2MigrationIssue(
                SnapshotV2MigrationIssueCode.UNSUPPORTED_TASK_TYPE,
                uid,
                "Habit v2 document ddl_uid '${document.ddlUid}' does not match its item uid.",
            )
            return null
        }
        val payload = document.habit
        val period = payload.period.toHabitPeriod(uid, issues)
        val goalType = payload.goalType.toHabitGoalType(uid, issues)
        val status = payload.status.toHabitStatus(uid, issues)
        return SnapshotV3HabitItem(
            uid = uid,
            ver = ver.toV3(),
            deleted = false,
            doc = SnapshotV3HabitDocument(
                habit = Habit(
                    uid = uid,
                    name = payload.name,
                    description = payload.description,
                    color = payload.color,
                    iconKey = payload.iconKey,
                    categoryUid = payload.categoryUid?.blankToNull(),
                    period = period,
                    timesPerPeriod = payload.timesPerPeriod.coerceAtLeast(1),
                    goalType = goalType,
                    totalTarget = payload.totalTarget?.takeIf { it > 0 },
                    status = status,
                    sortOrder = payload.sortOrder,
                    reminder = payload.alarmTime?.blankToNull()?.let(::HabitReminder),
                    createdAt = payload.createdAt.ifBlank { ver.ts },
                    updatedAt = payload.updatedAt.ifBlank { ver.ts },
                ),
                records = document.records.mapIndexed { index, record ->
                    HabitRecord(
                        uid = "$uid:record:$index:${record.date}",
                        habitUid = uid,
                        occurredOn = record.date,
                        count = record.count.coerceAtLeast(1),
                        status = record.status.toHabitRecordStatus(uid, issues),
                        createdAt = record.createdAt.ifBlank { payload.createdAt.ifBlank { ver.ts } },
                        updatedAt = record.createdAt.ifBlank { payload.updatedAt.ifBlank { ver.ts } },
                    )
                },
            ),
        )
    }

    private fun LegacyTaskSnapshotV2Item.toV3HabitFromCarrier(
        doc: LegacyTaskSnapshotV2Doc,
        issues: MutableList<SnapshotV2MigrationIssue>,
    ): SnapshotV3HabitItem {
        val metadata = doc.note.toLegacyHabitMetadataOrNull()
        if (doc.note.isNotBlank() && metadata == null) {
            issues += SnapshotV2MigrationIssue(
                SnapshotV2MigrationIssueCode.UNPARSEABLE_LEGACY_HABIT_METADATA,
                uid,
                "Legacy habit note was not valid HabitMetaData JSON; defaults were used.",
            )
        }
        issues += SnapshotV2MigrationIssue(
            SnapshotV2MigrationIssueCode.LEGACY_HABIT_CARRIER_FALLBACK,
            uid,
            "No dedicated habit v2 file was supplied; a legacy habit carrier was projected with recoverable defaults.",
        )
        val frequency = metadata?.frequency?.takeIf { it > 0 } ?: doc.habitCount.coerceAtLeast(1)
        val goalType = if (metadata?.frequencyType == "TOTAL" || doc.habitTotalCount > 0) HabitGoalType.TOTAL else HabitGoalType.PER_PERIOD
        val total = if (goalType == HabitGoalType.TOTAL) {
            metadata?.total?.takeIf { it > 0 } ?: doc.habitTotalCount.takeIf { it > 0 }
        } else {
            null
        }
        val createdAt = doc.timestamp.ifBlank { ver.ts }
        val updatedAt = ver.ts.ifBlank { createdAt }
        return SnapshotV3HabitItem(
            uid = uid,
            ver = ver.toV3(),
            deleted = false,
            doc = SnapshotV3HabitDocument(
                habit = Habit(
                    uid = uid,
                    name = doc.name,
                    categoryUid = doc.categoryUid?.blankToNull(),
                    period = metadata?.frequencyType.toHabitPeriodOrDaily(),
                    timesPerPeriod = frequency,
                    goalType = goalType,
                    totalTarget = total,
                    status = if (doc.state == "archived") HabitStatus.ARCHIVED else HabitStatus.ACTIVE,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                ),
                records = metadata?.completedDates.orEmpty().sorted().mapIndexed { index, date ->
                    HabitRecord(
                        uid = "$uid:legacy-record:$index:$date",
                        habitUid = uid,
                        occurredOn = date,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                    )
                },
            ),
        )
    }

    private fun MutableList<SnapshotV2MigrationIssue>.missingDocument(uid: String) {
        add(SnapshotV2MigrationIssue(SnapshotV2MigrationIssueCode.LIVE_ITEM_WITHOUT_DOCUMENT, uid, "Live v2 item has no doc and was skipped."))
    }

    private fun <T> MutableMap<String, T>.keepNewest(
        uid: String,
        candidate: T,
        version: (T) -> SnapshotV3EntityVersion,
        issues: MutableList<SnapshotV2MigrationIssue>,
    ) {
        val current = this[uid]
        if (current == null || version(candidate).compareTo(version(current)) > 0) {
            if (current != null) issues += SnapshotV2MigrationIssue(
                SnapshotV2MigrationIssueCode.DUPLICATE_UID_KEPT_NEWEST,
                uid,
                "Duplicate v2 uid encountered; the highest entity version was retained.",
            )
            this[uid] = candidate
        }
    }

    private fun SnapshotV3EntityVersion.compareTo(other: SnapshotV3EntityVersion): Int =
        compareValuesBy(this, other, SnapshotV3EntityVersion::ts, SnapshotV3EntityVersion::ctr, SnapshotV3EntityVersion::dev)

    private fun LegacySnapshotV2EntityVersion.toV3() = SnapshotV3EntityVersion(ts, ctr, dev)

    private fun String.toTaskState(uid: String, issues: MutableList<SnapshotV2MigrationIssue>): TaskState = when (this) {
        "active" -> TaskState.ACTIVE
        "completed" -> TaskState.COMPLETED
        "archived" -> TaskState.ARCHIVED
        "abandoned" -> TaskState.ABANDONED
        "abandonedArchived" -> TaskState.ABANDONED_ARCHIVED
        else -> {
            issues += SnapshotV2MigrationIssue(SnapshotV2MigrationIssueCode.UNKNOWN_TASK_STATE, uid, "Unknown task state '$this'; ACTIVE was used.")
            TaskState.ACTIVE
        }
    }

    private fun String.toHabitPeriod(uid: String, issues: MutableList<SnapshotV2MigrationIssue>): HabitPeriod = when (uppercase()) {
        "DAILY" -> HabitPeriod.DAILY
        "WEEKLY" -> HabitPeriod.WEEKLY
        "MONTHLY" -> HabitPeriod.MONTHLY
        "ONCE" -> HabitPeriod.ONCE
        "EBBINGHAUS" -> HabitPeriod.EBBINGHAUS
        else -> {
            issues += SnapshotV2MigrationIssue(SnapshotV2MigrationIssueCode.UNKNOWN_HABIT_PERIOD, uid, "Unknown habit period '$this'; DAILY was used.")
            HabitPeriod.DAILY
        }
    }

    private fun String.toHabitGoalType(uid: String, issues: MutableList<SnapshotV2MigrationIssue>): HabitGoalType = when (uppercase()) {
        "PER_PERIOD" -> HabitGoalType.PER_PERIOD
        "TOTAL" -> HabitGoalType.TOTAL
        else -> {
            issues += SnapshotV2MigrationIssue(SnapshotV2MigrationIssueCode.UNKNOWN_HABIT_GOAL_TYPE, uid, "Unknown habit goal type '$this'; PER_PERIOD was used.")
            HabitGoalType.PER_PERIOD
        }
    }

    private fun String.toHabitStatus(uid: String, issues: MutableList<SnapshotV2MigrationIssue>): HabitStatus = when (uppercase()) {
        "ACTIVE" -> HabitStatus.ACTIVE
        "ARCHIVED" -> HabitStatus.ARCHIVED
        else -> {
            issues += SnapshotV2MigrationIssue(SnapshotV2MigrationIssueCode.UNKNOWN_HABIT_STATUS, uid, "Unknown habit status '$this'; ACTIVE was used.")
            HabitStatus.ACTIVE
        }
    }

    private fun String.toHabitRecordStatus(uid: String, issues: MutableList<SnapshotV2MigrationIssue>): HabitRecordStatus = when (uppercase()) {
        "COMPLETED" -> HabitRecordStatus.COMPLETED
        "SKIPPED" -> HabitRecordStatus.SKIPPED
        "FAILED" -> HabitRecordStatus.FAILED
        else -> {
            issues += SnapshotV2MigrationIssue(SnapshotV2MigrationIssueCode.UNKNOWN_HABIT_RECORD_STATUS, uid, "Unknown record status '$this'; COMPLETED was used.")
            HabitRecordStatus.COMPLETED
        }
    }

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

    private fun String?.toHabitPeriodOrDaily(): HabitPeriod = when (this?.uppercase()) {
        "WEEKLY" -> HabitPeriod.WEEKLY
        "MONTHLY" -> HabitPeriod.MONTHLY
        "ONCE" -> HabitPeriod.ONCE
        "EBBINGHAUS" -> HabitPeriod.EBBINGHAUS
        else -> HabitPeriod.DAILY
    }

    private fun String.toLegacyHabitMetadataOrNull(): LegacyHabitMetadata? =
        runCatching { legacyHabitJson.decodeFromString(LegacyHabitMetadata.serializer(), this) }.getOrNull()
}

@Serializable
private data class LegacyHabitMetadata(
    val completedDates: Set<String> = emptySet(),
    val frequencyType: String = "DAILY",
    val frequency: Int = 1,
    val total: Int = 0,
)
