package com.deadliner.sync

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.asBoolean
import com.deadliner.db.repository.asLong
import com.deadliner.domain.category.Category
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitStatus
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.platform.PlatformIdentity
import com.deadliner.sync.snapshot.SnapshotV3EntityVersion
import com.deadliner.sync.snapshot.v2.LegacyCategorySnapshotV2Doc
import com.deadliner.sync.snapshot.v2.LegacyCategorySnapshotV2Item
import com.deadliner.sync.snapshot.v2.LegacyCategorySnapshotV2Root
import com.deadliner.sync.snapshot.v2.LegacyHabitRecordSnapshotV2Payload
import com.deadliner.sync.snapshot.v2.LegacyHabitSnapshotV2Doc
import com.deadliner.sync.snapshot.v2.LegacyHabitSnapshotV2Item
import com.deadliner.sync.snapshot.v2.LegacyHabitSnapshotV2Payload
import com.deadliner.sync.snapshot.v2.LegacyHabitSnapshotV2Root
import com.deadliner.sync.snapshot.v2.LegacySnapshotV2EntityVersion
import com.deadliner.sync.snapshot.v2.LegacySnapshotV2Version
import com.deadliner.sync.snapshot.v2.LegacyTaskSnapshotV2Doc
import com.deadliner.sync.snapshot.v2.LegacyTaskSnapshotV2Item
import com.deadliner.sync.snapshot.v2.LegacyTaskSnapshotV2Root
import com.deadliner.sync.snapshot.v2.LegacyTaskSnapshotV2Subtask
import com.deadliner.sync.snapshot.v2.SnapshotV2MigrationInput
import com.deadliner.sync.snapshot.v2.SnapshotV2Migrator
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Keeps the legacy three-file WebDAV V2 protocol alive during the Android/HarmonyOS transition.
 * Business records stay in KMP SQLite; only V2's per-entity LWW triple is persisted separately.
 */
class LegacyV2SyncFacade(
    private val database: AppDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun mergeRemotePayloads(
        taskPayload: String?,
        habitPayload: String?,
        categoryPayload: String?,
    ): LegacyV2SyncMergeResult = try {
        val remoteTasks = taskPayload?.takeIf(String::isNotBlank)?.let { json.decodeFromString<LegacyTaskSnapshotV2Root>(it) }
        val remoteHabits = habitPayload?.takeIf(String::isNotBlank)?.let { json.decodeFromString<LegacyHabitSnapshotV2Root>(it) }
        val remoteCategories = categoryPayload?.takeIf(String::isNotBlank)?.let { json.decodeFromString<LegacyCategorySnapshotV2Root>(it) }
        val localTasks = buildTaskRoot()
        val localHabits = buildHabitRoot()
        val localCategories = buildCategoryRoot()
        val mergedTasks = mergeTasks(localTasks, remoteTasks)
        val mergedHabits = mergeHabits(localHabits, remoteHabits)
        val mergedCategories = mergeCategories(localCategories, remoteCategories)
        val applied = applyIfRemoteNewer(mergedTasks, mergedHabits, mergedCategories)
        LegacyV2SyncMergeResult(
            taskPayload = json.encodeToString(mergedTasks),
            habitPayload = json.encodeToString(mergedHabits),
            categoryPayload = json.encodeToString(mergedCategories),
            remoteApplied = applied,
            remoteIgnored = remoteItemCount(remoteTasks, remoteHabits, remoteCategories) - applied,
        )
    } catch (error: Exception) {
        LegacyV2SyncMergeResult(
            taskPayload = "", habitPayload = "", categoryPayload = "",
            remoteApplied = 0, remoteIgnored = 0,
            failureMessage = "Invalid V2 sync payload: ${error.message ?: error::class.simpleName}",
        )
    }

    private fun buildTaskRoot(): LegacyTaskSnapshotV2Root = LegacyTaskSnapshotV2Root(
        version = rootVersion(),
        items = database.taskQueries.selectAllIncludingDeleted(::task).executeAsList().map { task ->
            val version = versionFor(TASK, task.uid, task.updatedAt)
            if (task.isDeleted) LegacyTaskSnapshotV2Item(task.uid, version, true)
            else LegacyTaskSnapshotV2Item(task.uid, version, false, task.toV2())
        },
    )

    private fun buildHabitRoot(): LegacyHabitSnapshotV2Root = LegacyHabitSnapshotV2Root(
        version = rootVersion(),
        items = database.habitQueries.selectAllIncludingDeleted(::habit).executeAsList().map { habit ->
            val version = versionFor(HABIT, habit.uid, habit.updatedAt)
            if (habit.isDeleted) LegacyHabitSnapshotV2Item(habit.uid, version, true)
            else LegacyHabitSnapshotV2Item(
                uid = habit.uid,
                ver = version,
                deleted = false,
                doc = LegacyHabitSnapshotV2Doc(
                    ddlUid = habit.uid,
                    habit = habit.toV2(),
                    records = database.habitRecordQueries.selectForHabit(habit.uid, ::record).executeAsList().map { it.toV2() },
                ),
            )
        },
    )

    private fun buildCategoryRoot(): LegacyCategorySnapshotV2Root = LegacyCategorySnapshotV2Root(
        version = rootVersion(),
        items = database.categoryQueries.selectAllIncludingDeleted(::category).executeAsList().map { category ->
            val version = versionFor(CATEGORY, category.uid, category.updatedAt)
            if (category.isDeleted) LegacyCategorySnapshotV2Item(category.uid, version, true)
            else LegacyCategorySnapshotV2Item(category.uid, version, false, category.toV2())
        },
    )

    private fun mergeTasks(local: LegacyTaskSnapshotV2Root, remote: LegacyTaskSnapshotV2Root?): LegacyTaskSnapshotV2Root =
        local.copy(items = merge(local.items, remote?.items.orEmpty()) { it.uid }, version = local.version)

    private fun mergeHabits(local: LegacyHabitSnapshotV2Root, remote: LegacyHabitSnapshotV2Root?): LegacyHabitSnapshotV2Root =
        local.copy(items = merge(local.items, remote?.items.orEmpty()) { it.uid }, version = local.version)

    private fun mergeCategories(local: LegacyCategorySnapshotV2Root, remote: LegacyCategorySnapshotV2Root?): LegacyCategorySnapshotV2Root =
        local.copy(items = merge(local.items, remote?.items.orEmpty()) { it.uid }, version = local.version)

    private fun <T> merge(local: List<T>, remote: List<T>, uid: (T) -> String): List<T> where T : Any {
        val result = linkedMapOf<String, T>()
        (local + remote).forEach { item ->
            val key = uid(item)
            val current = result[key]
            if (current == null || versionOf(item).isNewerThan(versionOf(current))) result[key] = item
        }
        return result.values.toList()
    }

    private fun versionOf(item: Any): LegacySnapshotV2EntityVersion = when (item) {
        is LegacyTaskSnapshotV2Item -> item.ver
        is LegacyHabitSnapshotV2Item -> item.ver
        is LegacyCategorySnapshotV2Item -> item.ver
        else -> error("Unsupported V2 item")
    }

    private fun applyIfRemoteNewer(
        tasks: LegacyTaskSnapshotV2Root,
        habits: LegacyHabitSnapshotV2Root,
        categories: LegacyCategorySnapshotV2Root,
    ): Int {
        val migrated = SnapshotV2Migrator.migrate(SnapshotV2MigrationInput(tasks, habits, categories)).snapshot
        var applied = 0
        database.transaction {
            migrated.categories.forEach { item ->
                if (shouldApplyIncoming(CATEGORY, item.uid, item.ver.toV2())) {
                    applyCategory(item.uid, item.deleted, item.doc, item.ver)
                    applied++
                }
            }
            migrated.tasks.forEach { item ->
                if (shouldApplyIncoming(TASK, item.uid, item.ver.toV2())) {
                    applyTask(item.uid, item.deleted, item.doc, item.ver)
                    applied++
                }
            }
            migrated.habits.forEach { item ->
                if (shouldApplyIncoming(HABIT, item.uid, item.ver.toV2())) {
                    applyHabit(item.uid, item.deleted, item.doc?.habit, item.doc?.records.orEmpty(), item.ver)
                    applied++
                }
            }
        }
        return applied
    }

    private fun applyCategory(uid: String, deleted: Boolean, value: Category?, version: SnapshotV3EntityVersion) {
        if (deleted) database.categoryQueries.softDeleteCategory(version.ts, uid)
        else value?.let {
            database.categoryQueries.insertCategory(it.uid, it.name, it.iconKey, it.colorHex, it.isPreset.asLong(), it.sortOrder.toLong(), it.createdAt, it.updatedAt, 0)
            database.categoryQueries.updateCategory(it.name, it.iconKey, it.colorHex, it.isPreset.asLong(), it.sortOrder.toLong(), it.updatedAt, 0, it.uid)
        }
        saveVersion(CATEGORY, uid, version.toV2())
    }

    private fun applyTask(uid: String, deleted: Boolean, value: Task?, version: SnapshotV3EntityVersion) {
        if (deleted) {
            database.taskQueries.softDeleteTask(version.ts, uid)
        } else value?.let { task ->
            database.taskQueries.insertTask(task.uid, task.title, task.note, task.startAt, task.dueAt, task.state.persisted(), task.completedAt, task.categoryUid, task.isStarred.asLong(), task.calendarEventId, task.createdAt, task.updatedAt, 0)
            database.taskQueries.updateTask(task.title, task.note, task.startAt, task.dueAt, task.state.persisted(), task.completedAt, task.categoryUid, task.isStarred.asLong(), task.calendarEventId, task.updatedAt, 0, task.uid)
            task.subtasks.forEach(::saveSubtask)
        }
        saveVersion(TASK, uid, version.toV2())
    }

    private fun applyHabit(uid: String, deleted: Boolean, value: Habit?, records: List<HabitRecord>, version: SnapshotV3EntityVersion) {
        if (deleted) {
            database.habitQueries.softDeleteHabit(version.ts, uid)
        } else value?.let { habit ->
            database.habitQueries.insertHabit(habit.uid, habit.name, habit.description, habit.color?.toLong(), habit.iconKey, habit.categoryUid, habit.period.name, habit.timesPerPeriod.toLong(), habit.goalType.name, habit.totalTarget?.toLong(), habit.status.name, habit.sortOrder.toLong(), habit.reminder?.localTime, habit.reminder?.isEnabled?.asLong(), habit.createdAt, habit.updatedAt, 0)
            database.habitQueries.updateHabit(habit.name, habit.description, habit.color?.toLong(), habit.iconKey, habit.categoryUid, habit.period.name, habit.timesPerPeriod.toLong(), habit.goalType.name, habit.totalTarget?.toLong(), habit.status.name, habit.sortOrder.toLong(), habit.reminder?.localTime, habit.reminder?.isEnabled?.asLong(), habit.updatedAt, 0, habit.uid)
            records.forEach(::saveRecord)
        }
        saveVersion(HABIT, uid, version.toV2())
    }

    private fun saveSubtask(value: TaskSubtask) {
        database.taskSubtaskQueries.insertSubtask(value.uid, value.taskUid, value.content, value.isCompleted.asLong(), value.sortOrder.toLong(), value.createdAt, value.updatedAt, value.isDeleted.asLong())
        database.taskSubtaskQueries.updateSubtask(value.taskUid, value.content, value.isCompleted.asLong(), value.sortOrder.toLong(), value.updatedAt, value.isDeleted.asLong(), value.uid)
    }

    private fun saveRecord(value: HabitRecord) {
        database.habitRecordQueries.insertHabitRecord(value.uid, value.habitUid, value.occurredOn, value.count.toLong(), value.status.name, value.createdAt, value.updatedAt, value.isDeleted.asLong())
        database.habitRecordQueries.updateHabitRecord(value.habitUid, value.occurredOn, value.count.toLong(), value.status.name, value.updatedAt, value.isDeleted.asLong(), value.uid)
    }

    private fun versionFor(kind: String, uid: String, updatedAt: String): LegacySnapshotV2EntityVersion {
        val stored = database.legacyV2VersionQueries.selectVersion(kind, uid) { ts, ctr, dev -> LegacySnapshotV2EntityVersion(ts, ctr.toInt(), dev) }.executeAsOneOrNull()
        if (stored != null && stored.ts >= updatedAt) return stored
        return LegacySnapshotV2EntityVersion(updatedAt.ifBlank { Clock.System.now().toString() }, 0, PlatformIdentity.deviceId()).also { saveVersion(kind, uid, it) }
    }

    private fun shouldApplyIncoming(kind: String, uid: String, incoming: LegacySnapshotV2EntityVersion): Boolean {
        val stored = database.legacyV2VersionQueries.selectVersion(kind, uid) { ts, ctr, dev ->
            LegacySnapshotV2EntityVersion(ts, ctr.toInt(), dev)
        }.executeAsOneOrNull()
        return stored == null || incoming.isNewerThan(stored)
    }

    private fun saveVersion(kind: String, uid: String, version: LegacySnapshotV2EntityVersion) {
        database.legacyV2VersionQueries.upsertVersion(kind, uid, version.ts, version.ctr.toLong(), version.dev)
    }

    private fun rootVersion() = LegacySnapshotV2Version(Clock.System.now().toString(), PlatformIdentity.deviceId())
    private fun LegacySnapshotV2EntityVersion.isNewerThan(other: LegacySnapshotV2EntityVersion): Boolean = compareValuesBy(this, other, LegacySnapshotV2EntityVersion::ts, LegacySnapshotV2EntityVersion::ctr, LegacySnapshotV2EntityVersion::dev) > 0
    private fun SnapshotV3EntityVersion.toV2() = LegacySnapshotV2EntityVersion(ts, ctr, dev)
    private fun TaskState.persisted(): String = when (this) { TaskState.ACTIVE -> "active"; TaskState.COMPLETED -> "completed"; TaskState.ARCHIVED -> "archived"; TaskState.ABANDONED -> "abandoned"; TaskState.ABANDONED_ARCHIVED -> "abandonedArchived" }
    private fun taskUpdatedAt(item: com.deadliner.sync.snapshot.SnapshotV3TaskItem) = item.doc?.updatedAt ?: item.ver.ts
    private fun habitUpdatedAt(item: com.deadliner.sync.snapshot.SnapshotV3HabitItem) = item.doc?.habit?.updatedAt ?: item.ver.ts
    private fun categoryUpdatedAt(item: com.deadliner.sync.snapshot.SnapshotV3CategoryItem) = item.doc?.updatedAt ?: item.ver.ts
    private fun remoteItemCount(t: LegacyTaskSnapshotV2Root?, h: LegacyHabitSnapshotV2Root?, c: LegacyCategorySnapshotV2Root?) = t?.items?.size.orZero() + h?.items?.size.orZero() + c?.items?.size.orZero()
    private fun Int?.orZero() = this ?: 0

    private fun Task.toV2() = LegacyTaskSnapshotV2Doc(uid.hashCode().toLong(), title, startAt.orEmpty(), dueAt.orEmpty(), state.persisted(), completedAt.orEmpty(), note, if (isStarred) 1 else 0, "task", 0, 0, calendarEventId ?: 0, createdAt, subtasks.map { LegacyTaskSnapshotV2Subtask(it.uid, it.content, if (it.isCompleted) 1 else 0, it.sortOrder, it.createdAt, it.updatedAt) }, categoryUid)
    private fun Habit.toV2() = LegacyHabitSnapshotV2Payload(name, description, color, iconKey, categoryUid, period.name, timesPerPeriod, goalType.name, totalTarget, createdAt, updatedAt, status.name, sortOrder, reminder?.localTime)
    private fun HabitRecord.toV2() = LegacyHabitRecordSnapshotV2Payload(occurredOn, count, status.name, createdAt)
    private fun Category.toV2() = LegacyCategorySnapshotV2Doc(name, iconKey, colorHex, if (isPreset) 1 else 0, sortOrder, createdAt, updatedAt)

    private fun task(uid: String, title: String, note: String, startAt: String?, dueAt: String?, state: String, completedAt: String?, categoryUid: String?, isStarred: Long, calendarEventId: Long?, createdAt: String, updatedAt: String, isDeleted: Long) = Task(uid, title, note, startAt, dueAt, state.toState(), completedAt, categoryUid, isStarred.asBoolean(), calendarEventId, createdAt, updatedAt, isDeleted.asBoolean(), database.taskSubtaskQueries.selectForTask(uid, ::subtask).executeAsList())
    private fun subtask(uid: String, taskUid: String, content: String, done: Long, order: Long, createdAt: String, updatedAt: String, deleted: Long) = TaskSubtask(uid, taskUid, content, done.asBoolean(), order.toInt(), createdAt, updatedAt, deleted.asBoolean())
    private fun habit(uid: String, name: String, description: String?, color: Long?, iconKey: String?, categoryUid: String?, period: String, times: Long, goal: String, total: Long?, status: String, order: Long, reminder: String?, enabled: Long?, createdAt: String, updatedAt: String, deleted: Long) = Habit(uid, name, description, color?.toInt(), iconKey, categoryUid, com.deadliner.domain.habit.HabitPeriod.valueOf(period), times.toInt(), com.deadliner.domain.habit.HabitGoalType.valueOf(goal), total?.toInt(), HabitStatus.valueOf(status), order.toInt(), reminder?.let { com.deadliner.domain.habit.HabitReminder(it, enabled?.asBoolean() ?: true) }, createdAt, updatedAt, deleted.asBoolean())
    private fun record(uid: String, habitUid: String, date: String, count: Long, status: String, createdAt: String, updatedAt: String, deleted: Long) = HabitRecord(uid, habitUid, date, count.toInt(), com.deadliner.domain.habit.HabitRecordStatus.valueOf(status), createdAt, updatedAt, deleted.asBoolean())
    private fun category(uid: String, name: String, icon: String, color: String, preset: Long, order: Long, createdAt: String, updatedAt: String, deleted: Long) = Category(uid, name, icon, color, preset.asBoolean(), order.toInt(), createdAt, updatedAt, deleted.asBoolean())
    private fun String.toState() = when (this) { "active" -> TaskState.ACTIVE; "completed" -> TaskState.COMPLETED; "archived" -> TaskState.ARCHIVED; "abandoned" -> TaskState.ABANDONED; "abandonedArchived" -> TaskState.ABANDONED_ARCHIVED; else -> TaskState.ACTIVE }

    private companion object { const val TASK = "task"; const val HABIT = "habit"; const val CATEGORY = "category" }
}

@Serializable
data class LegacyV2SyncMergeResult(
    val taskPayload: String,
    val habitPayload: String,
    val categoryPayload: String,
    val remoteApplied: Int,
    val remoteIgnored: Int,
    val failureMessage: String? = null,
)
