package com.deadliner.db

import com.deadliner.domain.focus.FocusTimerCommand
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.task.Task
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove

/** Exercise Native roots, not just business assertions before the next GC happens. */
@OptIn(NativeRuntimeApi::class)
class NativeDatabaseGcRegressionTest {
    private data class ValidatedResource(val name: String, val payload: List<String>) {
        init { require(name.isNotBlank()) }
    }

    @Test
    fun throwingCopyConstructorRestoresCallerRoots() {
        val resource = ValidatedResource("live", List(16) { "payload-$it" })
        repeat(24) {
            assertFailsWith<IllegalArgumentException> { resource.copy(name = "") }
            GC.collect()
            assertEquals("live", resource.name)
            assertEquals("payload-15", resource.payload.last())
        }
    }

    @Test
    fun pureKotlinExceptionPreservesLiveRoots() {
        repeat(24) { iteration ->
            val holder = arrayListOf("alive-$iteration")
            assertFailsWith<IllegalStateException> { error("pure Kotlin fixture") }
            GC.collect()
            assertEquals("alive-$iteration", holder.single())
        }
    }

    @Test
    fun coroutineExceptionPreservesLiveRoots() = runBlocking {
        repeat(24) { iteration ->
            val holder = arrayListOf("coroutine-$iteration")
            assertFailsWith<IllegalStateException> { error("coroutine Kotlin fixture") }
            GC.collect()
            assertEquals("coroutine-$iteration", holder.single())
        }
    }

    @Test
    fun symbolicatedExceptionPreservesLiveRoots() = runBlocking {
        repeat(4) { iteration ->
            val holder = arrayListOf("symbolicated-$iteration")
            val error = assertFailsWith<IllegalStateException> { error("symbolication fixture") }
            error.printStackTrace()
            GC.collect()
            assertEquals("symbolicated-$iteration", holder.single())
        }
    }

    @Test
    fun sqliteRollbackAndLegacySyncSurviveCollectionWithLiveAndClosedDatabases() = runBlocking {
        repeat(12) { iteration ->
            val path = "${NSTemporaryDirectory()}native-gc-${NSUUID().UUIDString}.db"
            val driver = MacosDatabaseDriverFactory(path, "native-gc-test").createDriver()
            val db = DeadlinerDatabase.create(object : DatabaseDriverFactory {
                override fun createDriver() = driver
            })
            try {
                val start = Instant.parse("2026-10-13T00:00:00Z")
                val task = Task("task-$iteration", "Native roots", createdAt = start.toString(), updatedAt = start.toString())
                db.tasks.create(task)
                GC.collect()
                assertEquals(task, db.tasks.find(task.uid))
                val timer = db.createFocusTimerService()
                timer.execute(FocusTimerCommand.Start(task.uid, FocusTimerMode.STOPWATCH), start)
                val saved = assertNotNull(timer.execute(FocusTimerCommand.Finish,
                    Instant.parse("2026-10-13T00:01:00Z")).session)

                // SQLiter's throw/reset/unlock path preceded the old bulk crashes.
                driver.execute(null, """CREATE TRIGGER reject_gc_insert BEFORE INSERT ON task
                    BEGIN SELECT RAISE(ABORT, 'native gc rollback fixture'); END""", 0)
                assertFailsWith<Exception> { db.tasks.create(task.copy(uid = "rejected-$iteration")) }
                GC.collect()
                assertEquals(task, db.tasks.find(task.uid))

                val exported = db.sync.mergeRemotePayload(null)
                assertEquals(null, exported.failureMessage)
                val legacy = exported.payload.replace("\"formatVersion\":6", "\"formatVersion\":5")
                    .replace("\"format_version\":6", "\"format_version\":5")
                val merged = db.sync.mergeRemotePayload(legacy)
                assertEquals(null, merged.failureMessage)
                GC.collect()
                assertEquals(saved, db.focusSessions.find(saved.uid))
            } finally {
                db.close()
                remove(path)
            }
            GC.collect()
        }
    }
}
