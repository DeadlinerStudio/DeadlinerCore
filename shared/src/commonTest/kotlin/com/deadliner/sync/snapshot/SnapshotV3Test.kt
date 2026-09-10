package com.deadliner.sync.snapshot

import com.deadliner.domain.category.Category
import com.deadliner.domain.focus.FocusCompletionKind
import com.deadliner.domain.focus.FocusSession
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.habit.HabitGoalWindow
import com.deadliner.domain.habit.HabitScheduleKind
import com.deadliner.domain.habit.HabitTrackingMode
import com.deadliner.domain.habit.HabitWeekdays
import com.deadliner.domain.task.Task
import com.deadliner.sync.snapshot.v2.SnapshotV2MigrationInput
import com.deadliner.sync.snapshot.v2.SnapshotV2Migrator
import com.deadliner.sync.snapshot.v2.LegacyCategorySnapshotV2Root
import com.deadliner.sync.snapshot.v2.LegacyHabitSnapshotV2Root
import com.deadliner.sync.snapshot.v2.LegacyTaskSnapshotV2Root
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SnapshotV3Test {
    @Test
    fun `v3 codec round trips typed documents and rejects invalid tombstones`() {
        val snapshot = SnapshotV3Root(
            version = SnapshotV3Version(ts = "2026-07-18T12:00:00Z", dev = "mac"),
            categories = listOf(
                SnapshotV3CategoryItem(
                    uid = "cat.work",
                    ver = SnapshotV3EntityVersion("2026-07-18T12:00:00Z", 2, "mac"),
                    deleted = false,
                    doc = Category("cat.work", "Work", "briefcase", "#123456", false, 0, "created", "updated"),
                ),
            ),
            tasks = listOf(
                SnapshotV3TaskItem(
                    uid = "task.1",
                    ver = SnapshotV3EntityVersion("2026-07-18T12:00:00Z", 3, "mac"),
                    deleted = false,
                    doc = Task(uid = "task.1", title = "Ship v3", createdAt = "created", updatedAt = "updated"),
                ),
            ),
            focusSessions = listOf(
                SnapshotV3FocusSessionItem(
                    uid = "focus.1",
                    ver = SnapshotV3EntityVersion("2026-07-18T12:20:00Z", 1, "mac"),
                    deleted = false,
                    doc = FocusSession(
                        uid = "focus.1",
                        taskUid = "task.1",
                        taskTitleSnapshot = "Ship v3",
                        mode = FocusTimerMode.STOPWATCH,
                        startedAt = "2026-07-18T12:00:00Z",
                        endedAt = "2026-07-18T12:20:00Z",
                        durationSeconds = 1_200,
                        completionKind = FocusCompletionKind.FOCUS_ONLY,
                        createdAt = "2026-07-18T12:20:00Z",
                    ),
                ),
            ),
        )

        val decoded = SnapshotV3Codec.decode(SnapshotV3Codec.encode(snapshot))
        assertEquals(snapshot, decoded)

        assertFailsWith<SnapshotV3ValidationException> {
            SnapshotV3Codec.encode(snapshot.copy(tasks = listOf(snapshot.tasks.single().copy(deleted = true))))
        }
    }

    @Test
    fun `migrates all iOS v2 snapshot files into one v3 root`() {
        val json = Json { ignoreUnknownKeys = false }
        val taskRoot = json.decodeFromString<LegacyTaskSnapshotV2Root>(
            """{
              "version":{"ts":"2026-01-01T00:00:00Z","dev":"ios"},
              "items":[
                {"uid":"task.1","ver":{"ts":"2026-01-01T00:00:01Z","ctr":1,"dev":"ios"},"deleted":false,"doc":{"id":1,"name":"Task","start_time":"","end_time":"2026-01-02","state":"completed","complete_time":"2026-01-01","note":"keep me","is_stared":1,"type":"task","habit_count":0,"habit_total_count":0,"calendar_event":0,"timestamp":"2026-01-01T00:00:00Z","sub_tasks":[{"id":"sub.1","content":"child","is_completed":1,"sort_order":4,"created_at":null,"updated_at":null}],"category_uid":"cat.1"}},
                {"uid":"habit.1","ver":{"ts":"2026-01-01T00:00:02Z","ctr":1,"dev":"ios"},"deleted":false,"doc":{"id":2,"name":"old carrier","start_time":"","end_time":"","state":"active","complete_time":"","note":"{}","is_stared":0,"type":"habit","habit_count":1,"habit_total_count":0,"calendar_event":0,"timestamp":"2026-01-01T00:00:00Z","sub_tasks":[],"category_uid":null}},
                {"uid":"unknown.deleted","ver":{"ts":"2026-01-01T00:00:03Z","ctr":1,"dev":"ios"},"deleted":true,"doc":null}
              ]
            }""",
        )
        val habitRoot = json.decodeFromString<LegacyHabitSnapshotV2Root>(
            """{
              "version":{"ts":"2026-01-03T00:00:00Z","dev":"ios"},
              "items":[{"uid":"habit.1","ver":{"ts":"2026-01-03T00:00:01Z","ctr":2,"dev":"ios"},"deleted":false,"doc":{"ddl_uid":"habit.1","habit":{"name":"Read","description":"Books","color":9,"icon_key":"book","category_uid":"cat.1","period":"WEEKLY","times_per_period":2,"goal_type":"PER_PERIOD","total_target":null,"created_at":"2026-01-01","updated_at":"2026-01-03","status":"ACTIVE","sort_order":3,"alarm_time":"09:00"},"records":[{"date":"2026-01-02","count":2,"status":"COMPLETED","created_at":"2026-01-02"}]}}]
            }""",
        )
        val categoryRoot = json.decodeFromString<LegacyCategorySnapshotV2Root>(
            """{
              "version":{"ts":"2026-01-02T00:00:00Z","dev":"ios"},
              "items":[
                {"uid":"cat.1","ver":{"ts":"2026-01-02T00:00:01Z","ctr":1,"dev":"ios"},"deleted":false,"doc":{"name":"Work","icon_key":"briefcase","color_hex":"#123456","is_preset":0,"sort_order":10,"created_at":"2026-01-01","updated_at":"2026-01-02"}},
                {"uid":"cat.deleted","ver":{"ts":"2026-01-02T00:00:02Z","ctr":1,"dev":"ios"},"deleted":true,"doc":null}
              ]
            }""",
        )

        val result = SnapshotV2Migrator.migrate(SnapshotV2MigrationInput(taskRoot, habitRoot, categoryRoot))

        assertEquals("2026-01-03T00:00:00Z", result.snapshot.version.ts)
        assertEquals(2, result.snapshot.categories.size)
        assertEquals(2, result.snapshot.tasks.size)
        assertEquals(1, result.snapshot.habits.size)
        val task = assertNotNull(result.snapshot.tasks.first { it.uid == "task.1" }.doc)
        assertEquals("2026-01-02", task.dueAt)
        assertEquals(1, task.subtasks.size)
        assertEquals("task.1", task.subtasks.single().taskUid)
        val habit = assertNotNull(result.snapshot.habits.single().doc)
        assertEquals("Read", habit.habit.name)
        assertEquals(HabitScheduleKind.WEEKLY_DAYS, habit.habit.schedule.kind)
        assertEquals(HabitWeekdays.ALL, habit.habit.schedule.weekdaysMask)
        assertEquals(HabitGoalWindow.WEEK, habit.habit.goal.window)
        assertEquals(2, habit.habit.goal.targetCount)
        assertEquals(HabitTrackingMode.QUANTITY, habit.habit.tracking.mode)
        assertEquals("09:00", habit.habit.reminder?.localTime)
        assertEquals("habit.1", habit.records.single().habitUid)
        assertTrue(result.issues.any { it.uid == "habit.1" && it.code.name == "LEGACY_HABIT_CARRIER_SKIPPED" })
        assertTrue(result.issues.any { it.uid == "unknown.deleted" && it.code.name == "AMBIGUOUS_TOMBSTONE_ASSUMED_TASK" })
        SnapshotV3Validator.requireValid(result.snapshot)
    }

    @Test
    fun `falls back to old habit carrier and expands completed dates`() {
        val taskRoot = Json.decodeFromString<LegacyTaskSnapshotV2Root>("""
            {"version":{"ts":"2026-01-01","dev":"ios"},"items":[{
              "uid":"legacy.habit","ver":{"ts":"2026-01-02","ctr":1,"dev":"ios"},"deleted":false,
              "doc":{"id":1,"name":"Stretch","start_time":"","end_time":"","state":"archived","complete_time":"",
                "note":"{\"completedDates\":[\"2026-01-01\",\"2026-01-02\"],\"frequencyType\":\"WEEKLY\",\"frequency\":3,\"total\":12}",
                "is_stared":0,"type":"habit","habit_count":0,"habit_total_count":0,"calendar_event":-1,
                "timestamp":"2026-01-01","sub_tasks":[],"category_uid":null}
            }]}
        """)

        val result = SnapshotV2Migrator.migrate(SnapshotV2MigrationInput(tasks = taskRoot))
        val document = assertNotNull(result.snapshot.habits.single().doc)
        assertEquals(2, document.records.size)
        assertEquals("WEEKLY", document.habit.period.name)
        assertEquals("PER_PERIOD", document.habit.goalType.name)
        assertEquals(3, document.habit.timesPerPeriod)
        assertEquals(HabitScheduleKind.WEEKLY_DAYS, document.habit.schedule.kind)
        assertEquals(HabitWeekdays.ALL, document.habit.schedule.weekdaysMask)
        assertEquals(HabitGoalWindow.WEEK, document.habit.goal.window)
        assertEquals(3, document.habit.goal.targetCount)
        assertEquals(HabitTrackingMode.QUANTITY, document.habit.tracking.mode)
        assertTrue(result.issues.any { it.code.name == "LEGACY_HABIT_CARRIER_FALLBACK" })
    }
}
