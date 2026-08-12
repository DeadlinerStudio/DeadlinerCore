package com.deadliner.lifi.tool

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.service.LifiToolExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove

class LifiToolExecutorIntegrationTest {
    @Test
    fun createsAndReadsTasksAndHabitsThroughSharedRepositories() {
        withDatabase { database ->
            val tools = LifiToolExecutor(database.tasks, database.habits)
            val taskResult = tools.execute(
                "create_task",
                """{"name":"Ship v6","dueTime":"2026-08-20 20:00","note":"KMP"}""",
            ).objectValue()
            assertTrue(taskResult.boolean("ok"))
            val taskUid = assertNotNull(taskResult["task"]?.jsonObject?.string("id"))
            assertEquals("Ship v6", database.tasks.find(taskUid)?.title)

            val habitResult = tools.execute(
                "create_habit",
                """{"name":"Read","period":"WEEKLY","timesPerPeriod":3,"goalType":"PER_PERIOD"}""",
            ).objectValue()
            assertTrue(habitResult.boolean("ok"))
            assertEquals("Read", database.habits.list().single().name)

            assertEquals(1, tools.execute("read_tasks", """{"timeRangeDays":30}""").objectValue()["tasks"]!!.jsonArray.size)
            assertEquals(1, tools.execute("read_habits", "{}").objectValue()["habits"]!!.jsonArray.size)

            val update = tools.execute(
                "update_deadline",
                """{"taskId":"$taskUid","newDueTime":"2026-08-22 09:30"}""",
            ).objectValue()
            assertTrue(update.boolean("ok"))
            assertEquals("2026-08-22T09:30", database.tasks.find(taskUid)?.dueAt)
        }
    }

    @Test
    fun batchReportsPartialFailureWithoutCompatibilityCoercion() {
        withDatabase { database ->
            val result = LifiToolExecutor(database.tasks, database.habits).execute(
                "create_habit",
                """{"habits":[{"name":"Run","period":"DAILY","timesPerPeriod":1,"goalType":"PER_PERIOD"},{"name":"Old alias","period":"daily","timesPerPeriod":1,"goalType":"frequency"}]}""",
            ).objectValue()
            assertFalse(result.boolean("ok"))
            assertTrue(result.boolean("partialSuccess"))
            assertEquals(1, database.habits.list().size)
        }
    }

    private fun withDatabase(block: (DeadlinerDatabase) -> Unit) {
        val path = "${NSTemporaryDirectory()}deadliner-lifi-tools-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "mac-test-device"))
        try {
            block(database)
        } finally {
            database.close()
            remove(path)
        }
    }

    private fun String.objectValue() = Json.parseToJsonElement(this).jsonObject
    private fun kotlinx.serialization.json.JsonObject.boolean(key: String) = this[key]!!.jsonPrimitive.content.toBoolean()
    private fun kotlinx.serialization.json.JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content
}
