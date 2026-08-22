package com.deadliner.db

import com.deadliner.domain.category.Category
import com.deadliner.domain.category.CategoryPresets
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.capture.CaptureSource
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitActionOutcome
import com.deadliner.domain.habit.HabitGoal
import com.deadliner.domain.habit.HabitGoalWindow
import com.deadliner.domain.habit.HabitSchedule
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitTracking
import com.deadliner.domain.habit.HabitTrackingMode
import com.deadliner.domain.habit.HabitWeekdays
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.account.UserProfile
import com.deadliner.domain.account.UserTier
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskAction
import com.deadliner.domain.task.TaskActionOutcome
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.model.ChangeOperation
import com.deadliner.sync.ChangeLogSyncPayload
import com.deadliner.sync.SyncResourceKind
import com.deadliner.sync.snapshot.SnapshotV3EntityVersion
import com.deadliner.sync.snapshot.SnapshotV3CategoryItem
import com.deadliner.sync.snapshot.SnapshotV3Root
import com.deadliner.sync.snapshot.SnapshotV3TaskItem
import com.deadliner.sync.snapshot.SnapshotV3Version
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class DatabaseIntegrationTest {
    @Test
    fun habitV5PersistsWeeklyScheduleAndRejectsOffDayRecording() {
        val path = "${NSTemporaryDirectory()}deadliner-habit-v5-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "habit-v5-test"))
        try {
            val habit = Habit(
                uid = "habit.workdays",
                name = "背单词",
                schedule = HabitSchedule(
                    HabitScheduleKind.WEEKLY_DAYS,
                    "2026-08-17",
                    weekdaysMask = HabitWeekdays.WEEKDAYS,
                ),
                goal = HabitGoal(HabitGoalWindow.SCHEDULED_DAY, 20),
                tracking = HabitTracking(HabitTrackingMode.QUANTITY, "words", 5),
                createdAt = NOW,
                updatedAt = NOW,
            )
            database.habits.create(habit)

            assertEquals(habit, database.habits.find(habit.uid))
            assertEquals(
                HabitActionOutcome.NOT_DUE,
                database.habits.addRecordAmount(habit.uid, "2026-08-22", 5, NOW).outcome,
            )
            assertEquals(
                HabitActionOutcome.APPLIED,
                database.habits.addRecordAmount(habit.uid, "2026-08-18", 5, NOW).outcome,
            )
            assertEquals(5, database.habits.records(habit.uid).single().count)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun memoryUserProfileSyncDoesNotOverwriteSettingsUser() {
        val sourcePath = "${NSTemporaryDirectory()}deadliner-memory-profile-source-${NSUUID().UUIDString}.db"
        val targetPath = "${NSTemporaryDirectory()}deadliner-memory-profile-target-${NSUUID().UUIDString}.db"
        val source = DeadlinerDatabase.create(MacosDatabaseDriverFactory(sourcePath, "android-test-device"))
        val target = DeadlinerDatabase.create(MacosDatabaseDriverFactory(targetPath, "ios-test-device"))
        try {
            source.memory.updateUserProfile("Prefers concise weekly plans")
            target.profiles.create(
                UserProfile(
                    uid = "settings-user",
                    nickname = "Aritx",
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )

            val result = target.sync.mergeRemotePayload(source.sync.mergeRemotePayload(null).payload)

            assertEquals("Prefers concise weekly plans", target.memory.getUserProfile())
            assertEquals("Aritx", target.profiles.find("settings-user")?.nickname)
            assertTrue(SyncResourceKind.MEMORY in result.appliedResources)
        } finally {
            source.close()
            target.close()
            remove(sourcePath)
            remove(targetPath)
        }
    }

    @Test
    fun captureSnapshotAppliesAcrossDevicesAndReportsCaptureResource() {
        val sourcePath = "${NSTemporaryDirectory()}deadliner-capture-sync-source-${NSUUID().UUIDString}.db"
        val targetPath = "${NSTemporaryDirectory()}deadliner-capture-sync-target-${NSUUID().UUIDString}.db"
        val source = DeadlinerDatabase.create(MacosDatabaseDriverFactory(sourcePath, "ohos-test-device"))
        val target = DeadlinerDatabase.create(MacosDatabaseDriverFactory(targetPath, "ios-test-device"))
        try {
            val capture = CaptureItem(
                uid = "capture.cross-device",
                text = "修脚下周二去复查",
                source = CaptureSource.MANUAL,
                createdAt = "2026-08-11T04:15:25Z",
                updatedAt = "2026-08-11T04:15:25Z",
            )
            source.captures.create(capture)

            val result = target.sync.mergeRemotePayload(source.sync.mergeRemotePayload(null).payload)

            assertEquals(capture, target.captures.find(capture.uid))
            assertTrue(SyncResourceKind.CAPTURE in result.appliedResources)
        } finally {
            source.close()
            target.close()
            remove(sourcePath)
            remove(targetPath)
        }
    }

    @Test
    fun habitCheckInAndCancellationAdvanceAggregateVersionAcrossDevices() {
        val sourcePath = "${NSTemporaryDirectory()}deadliner-habit-sync-source-${NSUUID().UUIDString}.db"
        val targetPath = "${NSTemporaryDirectory()}deadliner-habit-sync-target-${NSUUID().UUIDString}.db"
        val source = DeadlinerDatabase.create(MacosDatabaseDriverFactory(sourcePath, "ohos-test-device"))
        val target = DeadlinerDatabase.create(MacosDatabaseDriverFactory(targetPath, "ios-test-device"))
        try {
            val habit = Habit(
                uid = "habit.cross-device",
                name = "GRE 背单词",
                period = HabitPeriod.DAILY,
                createdAt = "2026-08-12T04:52:36Z",
                updatedAt = "2026-08-12T04:52:36Z",
            )
            source.habits.create(habit)
            target.sync.mergeRemotePayload(source.sync.mergeRemotePayload(null).payload)
            assertTrue(target.habits.records(habit.uid).isEmpty())

            source.habits.saveRecord(
                HabitRecord(
                    uid = "record.ohos",
                    habitUid = habit.uid,
                    occurredOn = "2026-08-12",
                    createdAt = "2026-08-12T13:00:24Z",
                    updatedAt = "2026-08-12T13:00:24Z",
                ),
                ChangeOperation.INSERT,
            )
            val checkedInPayload = source.sync.mergeRemotePayload(null).payload
            val checkedInSnapshot = requireNotNull(Json.decodeFromString<ChangeLogSyncPayload>(checkedInPayload).snapshot)
            assertEquals(
                "2026-08-12T13:00:24Z",
                checkedInSnapshot.habits.single { it.uid == habit.uid }.ver.ts,
            )

            target.sync.mergeRemotePayload(checkedInPayload)
            assertEquals(listOf("record.ohos"), target.habits.records(habit.uid).map { it.uid })

            source.habits.clearRecords(habit.uid, "2026-08-12", "2026-08-12T14:00:00Z")
            val cancelledPayload = source.sync.mergeRemotePayload(null).payload
            val cancelledSnapshot = requireNotNull(Json.decodeFromString<ChangeLogSyncPayload>(cancelledPayload).snapshot)
            val cancelledHabit = cancelledSnapshot.habits.single { it.uid == habit.uid }
            assertEquals("2026-08-12T14:00:00Z", cancelledHabit.ver.ts)
            assertTrue(cancelledHabit.doc?.records?.isEmpty() == true)

            target.sync.mergeRemotePayload(cancelledPayload)
            assertTrue(target.habits.records(habit.uid).isEmpty())
        } finally {
            source.close()
            target.close()
            remove(sourcePath)
            remove(targetPath)
        }
    }

    @Test
    fun categoryPresetsSeedOnceAndRestoreWithoutOverwritingLiveEdits() {
        val path = "${NSTemporaryDirectory()}deadliner-category-presets-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            assertEquals(CategoryPresets.all.map { it.uid }, database.categories.list().map { it.uid })

            val edited = requireNotNull(database.categories.find("preset.work")).copy(
                name = "我的工作",
                updatedAt = NOW,
            )
            database.categories.update(edited)
            database.categories.softDelete("preset.study", NOW)

            val restored = database.categories.restorePresets()

            assertEquals("我的工作", database.categories.find("preset.work")?.name)
            val restoredStudy = requireNotNull(database.categories.find("preset.study"))
            assertFalse(restoredStudy.isDeleted)
            assertEquals(CategoryPresets.all.first { it.uid == "preset.study" }.name, restoredStudy.name)
            assertEquals(8, restored.size)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun deletingAllCategoriesDoesNotSeedPresetsAgainOnReopen() {
        val path = "${NSTemporaryDirectory()}deadliner-category-presets-reopen-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            database.categories.list().forEach { category ->
                database.categories.softDelete(category.uid, NOW)
            }
        } finally {
            database.close()
        }

        val reopened = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            assertTrue(reopened.categories.list().isEmpty())
        } finally {
            reopened.close()
            remove(path)
        }
    }

    @Test
    fun snapshotRestoresTaskWhenIncrementalLogDoesNotContainIt() {
        val path = "${NSTemporaryDirectory()}deadliner-snapshot-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val task = Task("task.recovered", "Recovered from full snapshot", createdAt = NOW, updatedAt = NOW)
            val payload = ChangeLogSyncPayload(
                snapshot = SnapshotV3Root(
                    version = SnapshotV3Version(NOW, "remote-device"),
                    tasks = listOf(SnapshotV3TaskItem(task.uid, SnapshotV3EntityVersion(NOW, 0, "remote-device"), false, task)),
                ),
                changes = emptyList(),
            )

            val result = database.sync.mergeRemotePayload(Json.encodeToString(payload))

            assertEquals(1, result.remoteApplied)
            assertEquals(task, database.tasks.find(task.uid))
            assertTrue(Json.decodeFromString<ChangeLogSyncPayload>(result.payload).snapshot?.tasks?.any { it.uid == task.uid } == true)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun legacyChangeOnlyPayloadIsRejectedByV4ProtocolGate() {
        val path = "${NSTemporaryDirectory()}deadliner-legacy-payload-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val result = database.sync.mergeRemotePayload("""{"formatVersion":1,"changes":[]}""")

            assertEquals(0, result.remoteApplied)
            assertTrue(result.payload.isEmpty())
            assertTrue(result.failureMessage?.contains("Unsupported sync format 1") == true)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun fullSnapshotKeepsPresetCategoryAndIgnoresHybridChanges() {
        val path = "${NSTemporaryDirectory()}deadliner-category-snapshot-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val preset = Category("category.inbox", "收件箱", "tray", "#2563EB", true, 0, NOW, NOW)
            val payload = ChangeLogSyncPayload(
                formatVersion = ChangeLogSyncPayload.FORMAT_VERSION,
                snapshot = SnapshotV3Root(
                    version = SnapshotV3Version(NOW, "ios"),
                    categories = listOf(SnapshotV3CategoryItem(preset.uid, SnapshotV3EntityVersion(NOW, 1, "ios"), false, preset)),
                ),
                changes = listOf(
                    com.deadliner.model.ChangeLogEntry(
                        id = "unsafe-hybrid-change",
                        tableName = "category",
                        recordId = preset.uid,
                        operation = ChangeOperation.UPDATE,
                        changedFields = kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive("占位分类")) },
                        clientTimestamp = Long.MAX_VALUE,
                        serverTimestamp = null,
                        deviceId = "legacy",
                        synced = true,
                    ),
                ),
            )

            database.sync.mergeRemotePayload(Json.encodeToString(payload))

            assertEquals(preset, database.categories.find(preset.uid))
            val emitted = database.sync.mergeRemotePayload(null).payload
            assertTrue(Json.decodeFromString<ChangeLogSyncPayload>(emitted).changes.isEmpty())
            assertTrue("\"changes\"" !in emitted)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun taskActionsUseSharedStateMachineAndWriteOneUpdatedAggregate() {
        val path = "${NSTemporaryDirectory()}deadliner-action-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val task = Task("task.action", "Use the shared state machine", createdAt = NOW, updatedAt = NOW)
            database.tasks.create(task)

            val completed = database.tasks.applyAction(task.uid, TaskAction.MARK_COMPLETE, "2026-07-19T01:00:00Z")
            assertEquals(TaskActionOutcome.APPLIED, completed.outcome)
            assertEquals("2026-07-19T01:00:00Z", completed.task?.completedAt)
            assertEquals("2026-07-19T01:00:00Z", completed.task?.updatedAt)

            val archived = database.tasks.applyAction(task.uid, TaskAction.MARK_ARCHIVE, "2026-07-19T02:00:00Z")
            assertEquals(TaskActionOutcome.APPLIED, archived.outcome)
            assertEquals("2026-07-19T01:00:00Z", archived.task?.completedAt)

            val invalid = database.tasks.applyAction(task.uid, TaskAction.MARK_GIVE_UP, "2026-07-19T03:00:00Z")
            assertEquals(TaskActionOutcome.INVALID, invalid.outcome)
            assertEquals("2026-07-19T02:00:00Z", database.tasks.find(task.uid)?.updatedAt)
        } finally {
            database.close()
            remove(path)
        }
    }

    @Test
    fun persistsDomainAggregatesAndChangeLogWithNativeSqlite() {
        val path = "${NSTemporaryDirectory()}deadliner-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            val category = Category("category.work", "Work", "briefcase", "#3B82F6", false, 10, NOW, NOW)
            database.categories.create(category)

            val task = Task(
                uid = "task.1",
                title = "Ship native database",
                categoryUid = category.uid,
                createdAt = NOW,
                updatedAt = NOW,
                subtasks = listOf(
                    TaskSubtask("subtask.1", "task.1", "Define schema", createdAt = NOW, updatedAt = NOW),
                    TaskSubtask("subtask.2", "task.1", "Run macOS test", sortOrder = 1, createdAt = NOW, updatedAt = NOW),
                ),
            )
            database.tasks.create(task)

            val habit = Habit("habit.1", "Review sync log", categoryUid = category.uid,
                period = HabitPeriod.DAILY, createdAt = NOW, updatedAt = NOW)
            database.habits.create(habit)
            database.habits.saveRecord(HabitRecord("record.1", habit.uid, "2026-07-18", createdAt = NOW), ChangeOperation.INSERT)

            val capture = CaptureItem("capture.1", "Plan migration", CaptureSource.AI, NOW, NOW)
            val memory = MemoryFragment("memory.1", "Prefer UID-first persistence", createdAt = NOW, updatedAt = NOW)
            val profile = UserProfile("profile.1", "Aritx", tier = UserTier.GEEK, createdAt = NOW, updatedAt = NOW)
            database.captures.create(capture)
            database.memory.create(memory)
            database.profiles.create(profile)

            assertEquals(task, database.tasks.find(task.uid))
            assertEquals(habit, database.habits.find(habit.uid))
            assertEquals(listOf("record.1"), database.habits.records(habit.uid).map { it.uid })
            assertEquals(capture, database.captures.find(capture.uid))
            assertEquals(memory, database.memory.find(memory.uid))
            assertEquals(profile, database.profiles.find(profile.uid))
            assertEquals(9, database.changeLog.pending().size)

            database.categories.softDelete(category.uid, "2026-07-19T00:00:00Z")
            assertNull(database.categories.find(category.uid)?.takeUnless { it.isDeleted })
            assertNotNull(database.tasks.find(task.uid))
            assertEquals(10, database.changeLog.pending().size)

            database.tasks.delete(task.uid, "2026-07-19T00:00:00Z")
            database.habits.delete(habit.uid, "2026-07-19T00:00:00Z")
            assertTrue(database.tasks.find(task.uid)?.isDeleted == true)
            assertTrue(database.habits.find(habit.uid)?.isDeleted == true)
            assertTrue(database.tasks.list().isEmpty())
            assertTrue(database.habits.list().isEmpty())
            assertTrue(database.habits.records(habit.uid).isEmpty())
            assertEquals(15, database.changeLog.pending().size)

            database.captures.delete(capture.uid, "2026-07-19T00:00:00Z")
            database.memory.delete(memory.uid, "2026-07-19T00:00:00Z")
            database.profiles.delete(profile.uid, "2026-07-19T00:00:00Z")
            assertTrue(database.captures.list().isEmpty())
            assertTrue(database.memory.list().isEmpty())
            assertTrue(database.profiles.list().isEmpty())
            assertEquals(18, database.changeLog.pending().size)
        } finally {
            database.close()
            remove(path)
        }
    }

    private companion object {
        const val NOW = "2026-07-18T00:00:00Z"
    }
}
