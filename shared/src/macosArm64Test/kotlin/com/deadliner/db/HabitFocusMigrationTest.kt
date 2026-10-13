package com.deadliner.db

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.deadliner.db.repository.focus.ActiveFocusTimerStore
import com.deadliner.domain.focus.FocusTargetKind
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove
import kotlin.test.*

class HabitFocusMigrationTest {
    @Test fun schemaSevenRetainsTaskHistoryAndRunningTimerWhenAddingHabitTargets() {
        val name = "habit-focus-migration-${NSUUID().UUIDString}.db"
        val path = "${NSTemporaryDirectory()}$name"
        val driver = NativeSqliteDriver(LegacyFocusSchema, name, onConfiguration = { configuration ->
            configuration.copy(extendedConfig = configuration.extendedConfig.copy(basePath = NSTemporaryDirectory()))
        })
        try {
            driver.execute(null, """INSERT INTO focus_session VALUES
                ('session','task','Legacy Task','STOPWATCH',NULL,'2026-10-11T08:00:00Z','2026-10-11T08:02:00Z',120,
                 'FOCUS_ONLY','2026-10-11T08:02:00Z','2026-10-11T08:02:00Z',0)""", 0)
            driver.execute(null, """INSERT INTO active_focus_timer VALUES
                (1,'running','task','Legacy Task','COUNTDOWN',1500,'RUNNING','2026-10-11T09:00:00Z',
                 '2026-10-11T09:00:00Z',0,'2026-10-11T09:00:00Z')""", 0)
            AppDatabase.Schema.migrate(driver, 7, 8)
            val database = AppDatabase(driver)
            val saved = database.focusSessionQueries.selectAll().executeAsOne()
            assertEquals("session", saved.uid)
            assertEquals(120, saved.duration_seconds)
            assertEquals("TASK", saved.target_kind)
            assertNull(saved.habit_uid)
            val active = assertNotNull(ActiveFocusTimerStore(database).current())
            assertEquals("running", active.sessionUid)
            assertEquals(FocusTargetKind.TASK, active.boundTarget.kind)
            assertEquals("task", active.boundTarget.uid)
            assertEquals(1500, active.plannedDurationSeconds)
        } finally { driver.close(); remove(path) }
    }
}

/** Only the two schema-7 focus tables are needed to exercise the incremental migration. */
private object LegacyFocusSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version = 7L
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(null, """CREATE TABLE focus_session (
            uid TEXT NOT NULL PRIMARY KEY, task_uid TEXT NOT NULL, task_title_snapshot TEXT NOT NULL,
            mode TEXT NOT NULL, planned_duration_seconds INTEGER, started_at TEXT NOT NULL, ended_at TEXT NOT NULL,
            duration_seconds INTEGER NOT NULL, completion_kind TEXT NOT NULL, created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL, is_deleted INTEGER NOT NULL DEFAULT 0)""", 0)
        driver.execute(null, """CREATE TABLE active_focus_timer (
            singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1), session_uid TEXT NOT NULL,
            task_uid TEXT NOT NULL, task_title_snapshot TEXT NOT NULL, mode TEXT NOT NULL,
            planned_duration_seconds INTEGER, state TEXT NOT NULL, started_at TEXT NOT NULL,
            running_segment_started_at TEXT, accumulated_seconds INTEGER NOT NULL, updated_at TEXT NOT NULL)""", 0)
        return QueryResult.Value(Unit)
    }
    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion) = QueryResult.Value(Unit)
}
