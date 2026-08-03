package com.deadliner.sync.snapshot

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.ChangeLogRepository
import com.deadliner.db.repository.asBoolean
import com.deadliner.domain.account.UserProfile
import com.deadliner.domain.account.UserTier
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.capture.CaptureSource
import com.deadliner.domain.category.Category
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitGoalType
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitRecordStatus
import com.deadliner.domain.habit.HabitReminder
import com.deadliner.domain.habit.HabitScheduleItem
import com.deadliner.domain.habit.HabitScheduleState
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.model.ChangeLogEntry
import com.deadliner.model.ChangeOperation
import com.deadliner.platform.PlatformIdentity
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** SQLDelight-backed V3 full-state snapshot. The synchronization boundary owns its wire format. */
class SnapshotV3Store(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
) {
    fun export(): SnapshotV3Root = SnapshotV3Root(
        version = SnapshotV3Version(Clock.System.now().toString(), PlatformIdentity.deviceId()),
        categories = database.categoryQueries.selectAllIncludingDeleted(::category).executeAsList().map { item ->
            SnapshotV3CategoryItem(item.uid, versionFor("category", item.uid, item.updatedAt), item.isDeleted, item.takeUnless(Category::isDeleted))
        },
        tasks = database.taskQueries.selectAllIncludingDeleted(::task).executeAsList().map { item ->
            val withSubtasks = item.copy(subtasks = database.taskSubtaskQueries.selectAllForTask(item.uid, ::subtask).executeAsList().filterNot(TaskSubtask::isDeleted))
            SnapshotV3TaskItem(item.uid, versionFor("task", item.uid, item.updatedAt), item.isDeleted, withSubtasks.takeUnless(Task::isDeleted))
        },
        habits = database.habitQueries.selectAllIncludingDeleted(::habit).executeAsList().map { item ->
            val document = item.takeUnless(Habit::isDeleted)?.let { habit ->
                SnapshotV3HabitDocument(
                    habit = habit,
                    records = database.habitRecordQueries.selectForHabit(habit.uid, ::record).executeAsList(),
                    scheduleItems = database.habitScheduleQueries.selectForHabit(habit.uid, ::schedule).executeAsList(),
                )
            }
            SnapshotV3HabitItem(item.uid, versionFor("habit", item.uid, item.updatedAt), item.isDeleted, document)
        },
        captures = database.captureQueries.selectAllIncludingDeleted(::capture).executeAsList().map { item ->
            SnapshotV3CaptureItem(item.uid, versionFor("capture_item", item.uid, item.updatedAt), item.isDeleted, item.takeUnless(CaptureItem::isDeleted))
        },
        memoryFragments = database.memoryQueries.selectAllIncludingDeleted(::memory).executeAsList().map { item ->
            SnapshotV3MemoryFragmentItem(item.uid, versionFor("memory_fragment", item.uid, item.updatedAt), item.isDeleted, item.takeUnless(MemoryFragment::isDeleted))
        },
        userProfiles = database.userProfileQueries.selectAllIncludingDeleted(::profile).executeAsList().map { item ->
            SnapshotV3UserProfileItem(item.uid, versionFor("user_profile", item.uid, item.updatedAt), item.isDeleted, item.takeUnless(UserProfile::isDeleted))
        },
    )

    /** Converts a snapshot into normal immutable changes so it shares the existing LWW applier. */
    fun asChangeEntries(snapshot: SnapshotV3Root): List<ChangeLogEntry> = buildList {
        snapshot.categories.forEach { add(it.entry("category", it.doc)) }
        snapshot.tasks.forEach { item ->
            add(item.entry("task", item.doc))
            item.doc?.subtasks?.forEach { subtask -> add(documentEntry("task_subtask", subtask.uid, subtask.updatedAt, item.ver.dev, json.encodeToJsonElement(subtask).jsonObject)) }
        }
        snapshot.habits.forEach { item ->
            add(item.entry("habit", item.doc?.habit))
            item.doc?.records?.forEach { record -> add(documentEntry("habit_record", record.uid, record.updatedAt, item.ver.dev, json.encodeToJsonElement(record).jsonObject)) }
            item.doc?.scheduleItems?.forEach { schedule -> add(documentEntry("habit_schedule_item", schedule.uid, schedule.updatedAt, item.ver.dev, json.encodeToJsonElement(schedule).jsonObject)) }
        }
        snapshot.captures.forEach { add(it.entry("capture_item", it.doc)) }
        snapshot.memoryFragments.forEach { add(it.entry("memory_fragment", it.doc)) }
        snapshot.userProfiles.forEach { add(it.entry("user_profile", it.doc)) }
    }

    private fun documentEntry(table: String, uid: String, updatedAt: String, deviceId: String, document: kotlinx.serialization.json.JsonObject): ChangeLogEntry =
        ChangeLogEntry(
            id = "snapshot-v3:$table:$uid:$updatedAt:$deviceId",
            tableName = table,
            recordId = uid,
            operation = ChangeOperation.UPDATE,
            changedFields = document,
            clientTimestamp = timestamp(updatedAt),
            serverTimestamp = null,
            deviceId = deviceId,
            synced = true,
        )

    private fun SnapshotV3CategoryItem.entry(table: String, document: Category?): ChangeLogEntry = entry(table, uid, ver, deleted, document?.let { json.encodeToJsonElement(it).jsonObject })
    private fun SnapshotV3TaskItem.entry(table: String, document: Task?): ChangeLogEntry = entry(table, uid, ver, deleted, document?.let { json.encodeToJsonElement(it).jsonObject })
    private fun SnapshotV3HabitItem.entry(table: String, document: Habit?): ChangeLogEntry = entry(table, uid, ver, deleted, document?.let { json.encodeToJsonElement(it).jsonObject })
    private fun SnapshotV3CaptureItem.entry(table: String, document: CaptureItem?): ChangeLogEntry = entry(table, uid, ver, deleted, document?.let { json.encodeToJsonElement(it).jsonObject })
    private fun SnapshotV3MemoryFragmentItem.entry(table: String, document: MemoryFragment?): ChangeLogEntry = entry(table, uid, ver, deleted, document?.let { json.encodeToJsonElement(it).jsonObject })
    private fun SnapshotV3UserProfileItem.entry(table: String, document: UserProfile?): ChangeLogEntry = entry(table, uid, ver, deleted, document?.let { json.encodeToJsonElement(it).jsonObject })

    private fun entry(table: String, uid: String, version: SnapshotV3EntityVersion, deleted: Boolean, document: kotlinx.serialization.json.JsonObject?): ChangeLogEntry =
        ChangeLogEntry(
            id = "snapshot-v3:$table:$uid:${version.ts}:${version.ctr}:${version.dev}",
            tableName = table,
            recordId = uid,
            operation = if (deleted) ChangeOperation.DELETE else ChangeOperation.UPDATE,
            changedFields = document,
            clientTimestamp = timestamp(version.ts),
            serverTimestamp = null,
            deviceId = version.dev,
            synced = true,
        )

    private fun versionFor(table: String, uid: String, updatedAt: String): SnapshotV3EntityVersion {
        val latest = changeLog.latestFor(table, uid)
        return SnapshotV3EntityVersion(
            ts = latest?.clientTimestamp?.let { Instant.fromEpochMilliseconds(it).toString() } ?: updatedAt,
            ctr = 0,
            dev = latest?.deviceId ?: PlatformIdentity.deviceId(),
        )
    }

    private fun timestamp(raw: String): Long = runCatching { Instant.parse(raw).toEpochMilliseconds() }
        .getOrElse { Clock.System.now().toEpochMilliseconds() }

    private fun category(uid: String, name: String, iconKey: String, colorHex: String, isPreset: Long, sortOrder: Long, createdAt: String, updatedAt: String, isDeleted: Long) =
        Category(uid, name, iconKey, colorHex, isPreset.asBoolean(), sortOrder.toInt(), createdAt, updatedAt, isDeleted.asBoolean())
    private fun task(uid: String, title: String, note: String, startAt: String?, dueAt: String?, state: String, completedAt: String?, categoryUid: String?, isStarred: Long, calendarEventId: Long?, createdAt: String, updatedAt: String, isDeleted: Long) =
        Task(uid, title, note, startAt, dueAt, TaskState.entries.first { it.name.equals(state, true) || it.persistedValue == state }, completedAt, categoryUid, isStarred.asBoolean(), calendarEventId, createdAt, updatedAt, isDeleted.asBoolean())
    private fun subtask(uid: String, taskUid: String, content: String, isCompleted: Long, sortOrder: Long, createdAt: String, updatedAt: String, isDeleted: Long) =
        TaskSubtask(uid, taskUid, content, isCompleted.asBoolean(), sortOrder.toInt(), createdAt, updatedAt, isDeleted.asBoolean())
    private fun habit(uid: String, name: String, description: String?, color: Long?, iconKey: String?, categoryUid: String?, period: String, timesPerPeriod: Long, goalType: String, totalTarget: Long?, status: String, sortOrder: Long, reminderLocalTime: String?, reminderEnabled: Long?, createdAt: String, updatedAt: String, isDeleted: Long) =
        Habit(uid, name, description, color?.toInt(), iconKey, categoryUid, HabitPeriod.valueOf(period), timesPerPeriod.toInt(), HabitGoalType.valueOf(goalType), totalTarget?.toInt(), HabitStatus.valueOf(status), sortOrder.toInt(), reminderLocalTime?.let { HabitReminder(it, reminderEnabled?.asBoolean() ?: true) }, createdAt, updatedAt, isDeleted.asBoolean())
    private fun record(uid: String, habitUid: String, occurredOn: String, count: Long, status: String, createdAt: String, updatedAt: String, isDeleted: Long) =
        HabitRecord(uid, habitUid, occurredOn, count.toInt(), HabitRecordStatus.valueOf(status), createdAt, updatedAt, isDeleted.asBoolean())
    private fun schedule(uid: String, habitUid: String, dueAt: String, state: String, createdAt: String, updatedAt: String, isDeleted: Long) =
        HabitScheduleItem(uid, habitUid, dueAt, HabitScheduleState.valueOf(state), createdAt, updatedAt, isDeleted.asBoolean())
    private fun capture(uid: String, text: String, source: String, createdAt: String, updatedAt: String, isConsumed: Long, isDeleted: Long) =
        CaptureItem(uid, text, CaptureSource.valueOf(source), createdAt, updatedAt, isConsumed.asBoolean(), isDeleted.asBoolean())
    private fun memory(uid: String, content: String, category: String, createdAt: String, importance: Long, updatedAt: String, isDeleted: Long) =
        MemoryFragment(uid, content, category, createdAt, importance.toInt(), updatedAt, isDeleted.asBoolean())
    private fun profile(uid: String, nickname: String, avatarFileName: String?, tier: String, createdAt: String, updatedAt: String, isDeleted: Long) =
        UserProfile(uid, nickname, avatarFileName, UserTier.valueOf(tier), createdAt, updatedAt, isDeleted.asBoolean())

    private val TaskState.persistedValue: String get() = when (this) {
        TaskState.ACTIVE -> "active"; TaskState.COMPLETED -> "completed"; TaskState.ARCHIVED -> "archived"; TaskState.ABANDONED -> "abandoned"; TaskState.ABANDONED_ARCHIVED -> "abandonedArchived"
    }
}
