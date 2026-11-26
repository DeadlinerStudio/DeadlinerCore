package com.deadliner.sync.snapshot

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.asBoolean
import com.deadliner.db.repository.asLong
import com.deadliner.db.repository.habitFromSql
import com.deadliner.db.repository.upsertHabit
import com.deadliner.domain.account.UserProfile
import com.deadliner.domain.account.UserTier
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.capture.CaptureSource
import com.deadliner.domain.category.Category
import com.deadliner.domain.category.CategoryMutation
import com.deadliner.domain.focus.FocusCompletionKind
import com.deadliner.domain.focus.FocusTargetRef
import com.deadliner.domain.focus.FocusTargetKind
import com.deadliner.domain.focus.FocusSession
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.habit.HabitRecordStatus
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskState
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.platform.PlatformIdentity
import com.deadliner.sync.SyncResourceKind
import kotlinx.datetime.Clock

/**
 * The only V3 persistence protocol: a complete, per-entity LWW snapshot.
 *
 * Its versions deliberately live in a `v3/` namespace in the existing version table, so V2
 * migration state cannot affect V3 conflict resolution. No V3 synchronization path reads or
 * writes a changelog entry.
 */
class SnapshotV3Store(
    private val database: AppDatabase,
) {
    /**
     * One transaction gives a consistent read: without it a concurrent local write can land between
     * the category and habit/task queries, exporting a child whose category is missing.
     */
    fun export(): SnapshotV3Root = database.transactionWithResult { exportInTransaction() }

    private fun exportInTransaction(): SnapshotV3Root {
        val memoryUserProfile = database.memoryUserProfileQueries
            .selectProfileWithUpdatedAt { content, updatedAt -> content to updatedAt }
            .executeAsOneOrNull()
        return SnapshotV3Root(
            version = SnapshotV3Version(Clock.System.now().toString(), PlatformIdentity.deviceId()),
            categories = withStoredTombstones(
                database.categoryQueries.selectAllIncludingDeleted(::category).executeAsList().map { value ->
                    SnapshotV3CategoryItem(value.uid, versionFor(CATEGORY, value.uid, value.updatedAt), value.isDeleted, value.takeUnless(Category::isDeleted))
                }, CATEGORY, { it.uid }, { uid, version -> SnapshotV3CategoryItem(uid, version, true) },
            ),
            tasks = withStoredTombstones(
                database.taskQueries.selectAllIncludingDeleted(::task).executeAsList().map { value ->
                    val allSubtasks = database.taskSubtaskQueries.selectAllForTask(value.uid, ::subtask).executeAsList()
                    val document = value.copy(subtasks = allSubtasks.filterNot(TaskSubtask::isDeleted))
                    val aggregateUpdatedAt = sequenceOf(
                        sequenceOf(value.updatedAt),
                        allSubtasks.asSequence().map(TaskSubtask::updatedAt),
                    ).flatten().maxOrNull() ?: value.updatedAt
                    SnapshotV3TaskItem(value.uid, versionFor(TASK, value.uid, aggregateUpdatedAt), value.isDeleted, document.takeUnless(Task::isDeleted))
                }, TASK, { it.uid }, { uid, version -> SnapshotV3TaskItem(uid, version, true) },
            ),
            focusSessions = withStoredTombstones(
                database.focusSessionQueries.selectAllIncludingDeleted(::focusSession).executeAsList().map { value ->
                    SnapshotV3FocusSessionItem(
                        value.uid,
                        versionFor(FOCUS_SESSION, value.uid, value.updatedAt),
                        value.isDeleted,
                        value.takeUnless(FocusSession::isDeleted),
                    )
                },
                FOCUS_SESSION,
                { it.uid },
                { uid, version -> SnapshotV3FocusSessionItem(uid, version, true) },
            ),
            habits = withStoredTombstones(
                database.habitQueries.selectAllIncludingDeleted(::habitFromSql).executeAsList().map { value ->
                    val allRecords = database.habitRecordQueries.selectAllForHabit(value.uid, ::record).executeAsList()
                    val document = value.takeUnless(Habit::isDeleted)?.let { habit ->
                        SnapshotV3HabitDocument(
                            habit = habit,
                            records = allRecords.filterNot(HabitRecord::isDeleted),
                        )
                    }
                    val aggregateUpdatedAt = sequenceOf(
                        sequenceOf(value.updatedAt),
                        allRecords.asSequence().map(HabitRecord::updatedAt),
                    ).flatten().maxOrNull() ?: value.updatedAt
                    SnapshotV3HabitItem(value.uid, versionFor(HABIT, value.uid, aggregateUpdatedAt), value.isDeleted, document)
                }, HABIT, { it.uid }, { uid, version -> SnapshotV3HabitItem(uid, version, true) },
            ),
            captures = withStoredTombstones(
                database.captureQueries.selectAllIncludingDeleted(::capture).executeAsList().map { value ->
                    SnapshotV3CaptureItem(value.uid, versionFor(CAPTURE, value.uid, value.updatedAt), value.isDeleted, value.takeUnless(CaptureItem::isDeleted))
                }, CAPTURE, { it.uid }, { uid, version -> SnapshotV3CaptureItem(uid, version, true) },
            ),
            memoryFragments = withStoredTombstones(
                database.memoryQueries.selectAllIncludingDeleted(::memory).executeAsList().map { value ->
                    SnapshotV3MemoryFragmentItem(value.uid, versionFor(MEMORY, value.uid, value.updatedAt), value.isDeleted, value.takeUnless(MemoryFragment::isDeleted))
                }, MEMORY, { it.uid }, { uid, version -> SnapshotV3MemoryFragmentItem(uid, version, true) },
            ),
            memoryUserProfile = memoryUserProfile?.let { (content, updatedAt) ->
                SnapshotV3MemoryUserProfileItem(
                    ver = versionFor(MEMORY_PROFILE, MEMORY_PROFILE_UID, updatedAt),
                    content = content,
                )
            },
            userProfiles = withStoredTombstones(
                database.userProfileQueries.selectAllIncludingDeleted(::profile).executeAsList().map { value ->
                    SnapshotV3UserProfileItem(value.uid, versionFor(PROFILE, value.uid, value.updatedAt), value.isDeleted, value.takeUnless(UserProfile::isDeleted))
                }, PROFILE, { it.uid }, { uid, version -> SnapshotV3UserProfileItem(uid, version, true) },
            ),
        )
    }

    fun merge(
        remote: SnapshotV3Root?,
        afterMerge: () -> Unit = {},
    ): SnapshotV3MergeResult {
        if (remote == null) return SnapshotV3MergeResult(export(), 0, 0)
        var applied = 0
        var ignored = 0
        val appliedResources = mutableSetOf<SyncResourceKind>()
        database.transaction {
            remote.categories.forEach { item -> applyOrIgnore(CATEGORY, item.uid, item.ver, { applyCategory(item) }, { applied++; appliedResources += SyncResourceKind.CATEGORY }, { ignored++ }) }
            remote.tasks.forEach { item -> applyOrIgnore(TASK, item.uid, item.ver, { applyTask(item) }, { applied++; appliedResources += SyncResourceKind.TASK }, { ignored++ }) }
            remote.focusSessions.forEach { item -> applyOrIgnore(FOCUS_SESSION, item.uid, item.ver, { applyFocusSession(item) }, { applied++; appliedResources += SyncResourceKind.FOCUS_SESSION }, { ignored++ }) }
            remote.habits.forEach { item -> applyOrIgnore(HABIT, item.uid, item.ver, { applyHabit(item) }, { applied++; appliedResources += SyncResourceKind.HABIT }, { ignored++ }) }
            remote.captures.forEach { item -> applyOrIgnore(CAPTURE, item.uid, item.ver, { applyCapture(item) }, { applied++; appliedResources += SyncResourceKind.CAPTURE }, { ignored++ }) }
            remote.memoryFragments.forEach { item -> applyOrIgnore(MEMORY, item.uid, item.ver, { applyMemory(item) }, { applied++; appliedResources += SyncResourceKind.MEMORY }, { ignored++ }) }
            remote.memoryUserProfile?.let { item ->
                applyOrIgnore(MEMORY_PROFILE, MEMORY_PROFILE_UID, item.ver, { applyMemoryUserProfile(item) }, { applied++; appliedResources += SyncResourceKind.MEMORY }, { ignored++ })
            }
            remote.userProfiles.forEach { item -> applyOrIgnore(PROFILE, item.uid, item.ver, { applyProfile(item) }, { applied++; appliedResources += SyncResourceKind.PROFILE }, { ignored++ }) }
            afterMerge()
        }
        return SnapshotV3MergeResult(export(), applied, ignored, appliedResources)
    }

    internal fun replace(snapshot: SnapshotV3Root, afterReplace: () -> Unit = {}) {
        SnapshotV3Validator.requireValid(snapshot)
        database.transaction {
            database.taskSubtaskQueries.deleteAll()
            database.habitRecordQueries.deleteAll()
            database.activeFocusTimerQueries.deleteActive()
            database.focusSessionQueries.deleteAll()
            database.taskQueries.deleteAll()
            database.habitQueries.deleteAll()
            database.categoryQueries.deleteAll()
            database.captureQueries.deleteAll()
            database.memoryQueries.deleteAll()
            database.memoryUserProfileQueries.deleteProfile()
            database.userProfileQueries.deleteAll()
            database.changeLogQueries.deleteAll()
            database.legacyV2VersionQueries.deleteAll()
            database.serverSyncStateQueries.deleteAllOutbox()
            database.serverSyncStateQueries.deleteAllEntityStates()
            database.serverSyncStateQueries.deleteAllScopes()

            snapshot.categories.forEach { item -> applyOrIgnore(CATEGORY, item.uid, item.ver, { applyCategory(item) }, {}, {}) }
            snapshot.tasks.forEach { item -> applyOrIgnore(TASK, item.uid, item.ver, { applyTask(item) }, {}, {}) }
            snapshot.focusSessions.forEach { item -> applyOrIgnore(FOCUS_SESSION, item.uid, item.ver, { applyFocusSession(item) }, {}, {}) }
            snapshot.habits.forEach { item -> applyOrIgnore(HABIT, item.uid, item.ver, { applyHabit(item) }, {}, {}) }
            snapshot.captures.forEach { item -> applyOrIgnore(CAPTURE, item.uid, item.ver, { applyCapture(item) }, {}, {}) }
            snapshot.memoryFragments.forEach { item -> applyOrIgnore(MEMORY, item.uid, item.ver, { applyMemory(item) }, {}, {}) }
            snapshot.memoryUserProfile?.let { item ->
                applyOrIgnore(MEMORY_PROFILE, MEMORY_PROFILE_UID, item.ver, { applyMemoryUserProfile(item) }, {}, {})
            }
            snapshot.userProfiles.forEach { item -> applyOrIgnore(PROFILE, item.uid, item.ver, { applyProfile(item) }, {}, {}) }
            afterReplace()
        }
    }

    private fun applyCategory(item: SnapshotV3CategoryItem) {
        if (item.deleted) {
            // Keep a non-visible physical row for foreign-key integrity when an old task still
            // references this category. It is a tombstone, never a syncable live placeholder.
            database.categoryQueries.insertCategory(item.uid, "已删除分类", "folder", "#808080", false.asLong(), Int.MAX_VALUE.toLong(), item.ver.ts, item.ver.ts, true.asLong())
            database.categoryQueries.softDeleteCategory(item.ver.ts, item.uid)
        }
        else item.doc?.let { value ->
            // Historical snapshots can contain platform icon names (for example SF Symbols).
            // Persist only the shared semantic ID so every native client renders the same category.
            val normalizedIconKey = CategoryMutation.normalizedIconKey(value.iconKey)
            database.categoryQueries.insertCategory(value.uid, value.name, normalizedIconKey, value.colorHex, value.isPreset.asLong(), value.sortOrder.toLong(), value.createdAt, value.updatedAt, 0)
            database.categoryQueries.updateCategory(value.name, normalizedIconKey, value.colorHex, value.isPreset.asLong(), value.sortOrder.toLong(), value.updatedAt, 0, value.uid)
        }
    }

    private fun applyTask(item: SnapshotV3TaskItem) {
        if (item.deleted) {
            database.taskQueries.softDeleteTask(item.ver.ts, item.uid)
            return
        }
        item.doc?.let { value ->
            database.taskQueries.insertTask(value.uid, value.title, value.note, value.startAt, value.dueAt, value.state.persisted(), value.completedAt, value.categoryUid, value.isStarred.asLong(), value.calendarEventId, value.createdAt, value.updatedAt, 0)
            database.taskQueries.updateTask(value.title, value.note, value.startAt, value.dueAt, value.state.persisted(), value.completedAt, value.categoryUid, value.isStarred.asLong(), value.calendarEventId, value.updatedAt, 0, value.uid)
            val incomingSubtaskUids = value.subtasks.mapTo(mutableSetOf(), TaskSubtask::uid)
            database.taskSubtaskQueries.selectAllForTask(value.uid, ::subtask).executeAsList()
                .filter { !it.isDeleted && it.uid !in incomingSubtaskUids }
                .forEach { database.taskSubtaskQueries.softDeleteSubtask(item.ver.ts, it.uid) }
            value.subtasks.forEach(::saveSubtask)
        }
    }

    private fun applyFocusSession(item: SnapshotV3FocusSessionItem) {
        if (item.deleted) {
            database.focusSessionQueries.softDeleteSession(item.ver.ts, item.uid)
            return
        }
        item.doc?.let { value ->
            database.focusSessionQueries.insertSession(
                value.uid,
                value.taskUid,
                value.taskTitleSnapshot,
                value.mode.name,
                value.plannedDurationSeconds,
                value.startedAt,
                value.endedAt,
                value.durationSeconds,
                value.completionKind.name,
                value.createdAt,
                value.updatedAt,
                0,
                value.boundTarget.kind.name,
                value.target?.takeIf { it.kind == FocusTargetKind.HABIT }?.uid,
                value.habitOccurredOn,
                value.habitRecordedAmount?.toLong(),
            )
            database.focusSessionQueries.updateSession(
                value.taskUid,
                value.taskTitleSnapshot,
                value.mode.name,
                value.plannedDurationSeconds,
                value.startedAt,
                value.endedAt,
                value.durationSeconds,
                value.completionKind.name,
                value.updatedAt,
                0,
                value.boundTarget.kind.name,
                value.target?.takeIf { it.kind == FocusTargetKind.HABIT }?.uid,
                value.habitOccurredOn,
                value.habitRecordedAmount?.toLong(),
                value.uid,
            )
        }
    }

    private fun applyHabit(item: SnapshotV3HabitItem) {
        if (item.deleted) {
            database.habitQueries.softDeleteHabit(item.ver.ts, item.uid)
            return
        }
        item.doc?.let { document ->
            val value = document.habit
            database.upsertHabit(value, deleted = false)
            val incomingRecordUids = document.records.mapTo(mutableSetOf(), HabitRecord::uid)
            database.habitRecordQueries.selectForHabit(value.uid, ::record).executeAsList()
                .filter { it.uid !in incomingRecordUids }
                .forEach { database.habitRecordQueries.softDeleteHabitRecord(item.ver.ts, it.uid) }
            document.records.forEach(::saveRecord)
        }
    }

    private fun applyCapture(item: SnapshotV3CaptureItem) {
        if (item.deleted) database.captureQueries.softDeleteCapture(item.ver.ts, item.uid)
        else item.doc?.let { value ->
            database.captureQueries.insertCapture(value.uid, value.text, value.source.name, value.createdAt, value.updatedAt, value.isConsumed.asLong(), 0)
            database.captureQueries.updateCapture(value.text, value.source.name, value.updatedAt, value.isConsumed.asLong(), 0, value.uid)
        }
    }

    private fun applyMemory(item: SnapshotV3MemoryFragmentItem) {
        if (item.deleted) database.memoryQueries.softDeleteMemory(item.ver.ts, item.uid)
        else item.doc?.let { value ->
            database.memoryQueries.insertMemory(value.uid, value.content, value.category, value.createdAt, value.importance.toLong(), value.updatedAt, 0)
            database.memoryQueries.updateMemory(value.content, value.category, value.importance.toLong(), value.updatedAt, 0, value.uid)
        }
    }

    private fun applyMemoryUserProfile(item: SnapshotV3MemoryUserProfileItem) {
        database.memoryUserProfileQueries.upsertProfile(item.content, item.ver.ts)
    }

    private fun applyProfile(item: SnapshotV3UserProfileItem) {
        if (item.deleted) database.userProfileQueries.softDeleteUserProfile(item.ver.ts, item.uid)
        else item.doc?.let { value ->
            database.userProfileQueries.insertUserProfile(value.uid, value.nickname, value.avatarFileName, value.tier.name, value.createdAt, value.updatedAt, 0)
            database.userProfileQueries.updateUserProfile(value.nickname, value.avatarFileName, value.tier.name, value.updatedAt, 0, value.uid)
        }
    }

    private fun saveSubtask(value: TaskSubtask) {
        database.taskSubtaskQueries.insertSubtask(value.uid, value.taskUid, value.content, value.isCompleted.asLong(), value.sortOrder.toLong(), value.createdAt, value.updatedAt, value.isDeleted.asLong())
        database.taskSubtaskQueries.updateSubtask(value.taskUid, value.content, value.isCompleted.asLong(), value.sortOrder.toLong(), value.updatedAt, value.isDeleted.asLong(), value.uid)
    }

    private fun saveRecord(value: HabitRecord) {
        database.habitRecordQueries.insertHabitRecord(value.uid, value.habitUid, value.occurredOn, value.count.toLong(), value.status.name, value.createdAt, value.updatedAt, value.isDeleted.asLong())
        database.habitRecordQueries.updateHabitRecord(value.habitUid, value.occurredOn, value.count.toLong(), value.status.name, value.updatedAt, value.isDeleted.asLong(), value.uid)
    }

    private fun applyOrIgnore(kind: String, uid: String, incoming: SnapshotV3EntityVersion, apply: () -> Unit, accepted: () -> Unit, rejected: () -> Unit) {
        if (!isNewerThanStored(kind, uid, incoming)) {
            rejected()
            return
        }
        apply()
        saveVersion(kind, uid, incoming)
        accepted()
    }

    private fun isNewerThanStored(kind: String, uid: String, incoming: SnapshotV3EntityVersion): Boolean {
        val stored = storedVersion(kind, uid) ?: return true
        return incoming.compareTo(stored) > 0
    }

    private fun versionFor(kind: String, uid: String, updatedAt: String): SnapshotV3EntityVersion {
        val stored = storedVersion(kind, uid)
        val localTimestamp = updatedAt.ifBlank { Clock.System.now().toString() }
        if (stored != null && stored.ts >= localTimestamp) return stored
        return SnapshotV3EntityVersion(localTimestamp, 0, PlatformIdentity.deviceId()).also { saveVersion(kind, uid, it) }
    }

    private fun storedVersion(kind: String, uid: String): SnapshotV3EntityVersion? =
        database.legacyV2VersionQueries.selectVersion(v3Kind(kind), uid) { ts, ctr, dev -> SnapshotV3EntityVersion(ts, ctr.toInt(), dev) }.executeAsOneOrNull()

    private fun saveVersion(kind: String, uid: String, version: SnapshotV3EntityVersion) {
        database.legacyV2VersionQueries.upsertVersion(v3Kind(kind), uid, version.ts, version.ctr.toLong(), version.dev)
    }

    private fun <T> withStoredTombstones(items: List<T>, kind: String, uid: (T) -> String, tombstone: (String, SnapshotV3EntityVersion) -> T): List<T> {
        val known = items.mapTo(mutableSetOf(), uid)
        return items + database.legacyV2VersionQueries.selectVersionsForKind(v3Kind(kind)) { itemUid, ts, ctr, dev ->
            itemUid to SnapshotV3EntityVersion(ts, ctr.toInt(), dev)
        }.executeAsList().filter { (itemUid, _) -> itemUid !in known }.map { (itemUid, version) -> tombstone(itemUid, version) }
    }

    private fun SnapshotV3EntityVersion.compareTo(other: SnapshotV3EntityVersion): Int =
        compareValuesBy(this, other, SnapshotV3EntityVersion::ts, SnapshotV3EntityVersion::ctr, SnapshotV3EntityVersion::dev)

    private fun v3Kind(kind: String) = "v3/$kind"
    private fun TaskState.persisted(): String = when (this) { TaskState.ACTIVE -> "active"; TaskState.COMPLETED -> "completed"; TaskState.ARCHIVED -> "archived"; TaskState.ABANDONED -> "abandoned"; TaskState.ABANDONED_ARCHIVED -> "abandonedArchived" }
    private fun category(uid: String, name: String, iconKey: String, colorHex: String, isPreset: Long, sortOrder: Long, createdAt: String, updatedAt: String, isDeleted: Long) = Category(uid, name, iconKey, colorHex, isPreset.asBoolean(), sortOrder.toInt(), createdAt, updatedAt, isDeleted.asBoolean())
    private fun task(uid: String, title: String, note: String, startAt: String?, dueAt: String?, state: String, completedAt: String?, categoryUid: String?, isStarred: Long, calendarEventId: Long?, createdAt: String, updatedAt: String, isDeleted: Long) = Task(uid, title, note, startAt, dueAt, state.toTaskState(), completedAt, categoryUid, isStarred.asBoolean(), calendarEventId, createdAt, updatedAt, isDeleted.asBoolean())
    private fun subtask(uid: String, taskUid: String, content: String, isCompleted: Long, sortOrder: Long, createdAt: String, updatedAt: String, isDeleted: Long) = TaskSubtask(uid, taskUid, content, isCompleted.asBoolean(), sortOrder.toInt(), createdAt, updatedAt, isDeleted.asBoolean())
    private fun record(uid: String, habitUid: String, occurredOn: String, count: Long, status: String, createdAt: String, updatedAt: String, isDeleted: Long) = HabitRecord(uid, habitUid, occurredOn, count.toInt(), HabitRecordStatus.valueOf(status), createdAt, updatedAt, isDeleted.asBoolean())
    private fun capture(uid: String, text: String, source: String, createdAt: String, updatedAt: String, isConsumed: Long, isDeleted: Long) = CaptureItem(uid, text, CaptureSource.valueOf(source), createdAt, updatedAt, isConsumed.asBoolean(), isDeleted.asBoolean())
    private fun memory(uid: String, content: String, category: String, createdAt: String, importance: Long, updatedAt: String, isDeleted: Long) = MemoryFragment(uid, content, category, createdAt, importance.toInt(), updatedAt, isDeleted.asBoolean())
    private fun profile(uid: String, nickname: String, avatarFileName: String?, tier: String, createdAt: String, updatedAt: String, isDeleted: Long) = UserProfile(uid, nickname, avatarFileName, UserTier.valueOf(tier), createdAt, updatedAt, isDeleted.asBoolean())
    private fun focusSession(uid: String, taskUid: String, taskTitleSnapshot: String, mode: String, plannedDurationSeconds: Long?, startedAt: String, endedAt: String, durationSeconds: Long, completionKind: String, createdAt: String, updatedAt: String, isDeleted: Long, targetKind: String, habitUid: String?, habitOccurredOn: String?, habitRecordedAmount: Long?) =
        FocusSession(uid, taskUid, taskTitleSnapshot, FocusTimerMode.valueOf(mode), plannedDurationSeconds, startedAt, endedAt, durationSeconds, FocusCompletionKind.valueOf(completionKind), createdAt, updatedAt, isDeleted.asBoolean(),
            if (targetKind == "HABIT") FocusTargetRef(FocusTargetKind.HABIT, requireNotNull(habitUid)) else null, habitOccurredOn, habitRecordedAmount?.toInt())
    private fun String.toTaskState() = when (this) { "active" -> TaskState.ACTIVE; "completed" -> TaskState.COMPLETED; "archived" -> TaskState.ARCHIVED; "abandoned" -> TaskState.ABANDONED; "abandonedArchived" -> TaskState.ABANDONED_ARCHIVED; else -> error("Unknown persisted TaskState: $this") }

    private companion object {
        const val CATEGORY = "category"; const val TASK = "task"; const val FOCUS_SESSION = "focus_session"; const val HABIT = "habit"; const val CAPTURE = "capture_item"; const val MEMORY = "memory_fragment"; const val MEMORY_PROFILE = "memory_user_profile"; const val MEMORY_PROFILE_UID = "singleton"; const val PROFILE = "user_profile"
    }
}

data class SnapshotV3MergeResult(
    val snapshot: SnapshotV3Root,
    val remoteApplied: Int,
    val remoteIgnored: Int,
    val appliedResources: Set<SyncResourceKind> = emptySet(),
)
