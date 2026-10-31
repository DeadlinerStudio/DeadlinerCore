package com.deadliner.db

import com.deadliner.bridge.TaskDeadlineJsonBridge
import com.deadliner.domain.task.Task
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import platform.Foundation.NSUUID
import platform.Foundation.NSTemporaryDirectory
import platform.posix.remove

class TaskDeadlineJsonBridgeTest {
    @Test fun previewNeverWritesAndConfirmAccumulatesFromLatestStoredDeadline() = withDatabase { database ->
        val task = Task("deadline-bridge", "Keep title", note = "Keep note", dueAt = "2026-10-19T23:00",
            isStarred = true, createdAt = "2026-10-17T01:00:00Z", updatedAt = "2026-10-17T01:00:00Z")
        database.tasks.create(task)
        val bridge = TaskDeadlineJsonBridge(database.tasks)
        val preview = Json.parseToJsonElement(bridge.execute(request("PREVIEW", 3, "HOURS"))).jsonObject
        assertEquals("PREVIEW", preview["outcome"]!!.jsonPrimitive.content)
        assertEquals(LocalDateTime.parse("2026-10-20T02:00"), LocalDateTime.parse(preview["afterDeadline"]!!.jsonPrimitive.content))
        assertEquals(task, database.tasks.find(task.uid))
        // Another client changes the deadline after preview. Confirm must use the stored value.
        database.tasks.postponeOneDay(task.uid, "2026-10-19T01:00:00Z")
        repeat(2) {
            val response = Json.parseToJsonElement(bridge.execute(request("POSTPONE", 3, "HOURS"))).jsonObject
            assertEquals("APPLIED", response["outcome"]!!.jsonPrimitive.content)
        }
        val stored = database.tasks.find(task.uid)!!
        assertEquals(LocalDateTime.parse("2026-10-21T05:00"), LocalDateTime.parse(stored.dueAt!!))
        assertEquals(task.copy(dueAt = stored.dueAt, updatedAt = stored.updatedAt), stored)
    }

    @Test fun invalidRequestsAndMissingTasksCannotWrite() = withDatabase { database ->
        val task = Task("deadline-bridge", "No deadline", createdAt = "2026-10-17T01:00:00Z", updatedAt = "2026-10-17T01:00:00Z")
        database.tasks.create(task)
        val bridge = TaskDeadlineJsonBridge(database.tasks)
        fun outcome(payload: String) = Json.parseToJsonElement(bridge.execute(payload)).jsonObject["outcome"]!!.jsonPrimitive.content
        assertEquals("INVALID_DELAY", outcome(request("POSTPONE", 169, "HOURS")))
        assertEquals("INVALID_DELAY", outcome(request("POSTPONE", 8, "DAYS")))
        assertEquals("INVALID_DELAY", outcome(request("POSTPONE", 0, "DAYS")))
        assertEquals("INVALID_DELAY", outcome(request("POSTPONE", 1, "WEEKS")))
        assertEquals("INVALID_DEADLINE", outcome(request("PREVIEW", 1, "DAYS")))
        assertEquals("INVALID_DEADLINE", outcome(request("POSTPONE", 1, "DAYS")))
        assertEquals("NOT_FOUND", outcome(request("POSTPONE", 1, "DAYS").replace("deadline-bridge", "missing")))
        assertFails { bridge.execute(request("POSTPONE", 1, "DAYS").replace(":1,", ":1.5,")) }
        assertFails { bridge.execute(request("UNSUPPORTED", 1, "DAYS")) }
        assertEquals(task, database.tasks.find(task.uid))
    }

    private fun request(action: String, amount: Int, unit: String) =
        """{"action":"$action","taskUid":"deadline-bridge","amount":$amount,"unit":"$unit"}"""

    private fun withDatabase(block: (DeadlinerDatabase) -> Unit) {
        val path = "${NSTemporaryDirectory()}deadline-bridge-${NSUUID().UUIDString}.db"
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, "deadline-bridge-test"))
        try { block(database) } finally { database.close(); remove(path) }
    }
}
