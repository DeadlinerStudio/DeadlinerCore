package com.deadliner.lifi.tool

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.platform.DailySummaryConfig
import com.deadliner.platform.HabitNotificationConfig
import com.deadliner.platform.HabitNotificationInfo
import com.deadliner.platform.NotificationGateway
import com.deadliner.platform.TaskNotificationConfig
import com.deadliner.platform.TaskNotificationInfo
import com.deadliner.platform.UpcomingLiveUpdateConfig
import com.deadliner.service.LifiToolExecutor
import com.deadliner.service.NotificationOrchestrator
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
            val notifications = RecordingNotificationGateway()
            val tools = LifiToolExecutor(
                database.tasks,
                database.habits,
                notificationOrchestrator = NotificationOrchestrator(notifications),
            )
            val taskResult = tools.execute(
                "create_task",
                """{"name":"Ship v6","dueTime":"2026-08-20 20:00","note":"KMP"}""",
            ).objectValue()
            assertTrue(taskResult.boolean("ok"))
            val taskUid = assertNotNull(taskResult["task"]?.jsonObject?.string("id"))
            assertEquals("Ship v6", database.tasks.find(taskUid)?.title)

            val habitResult = tools.execute(
                "create_habit",
                """{"name":"Read","schedule":{"kind":"WEEKLY_DAYS","startOn":"2026-08-19","weekdaysMask":31},"goal":{"window":"SCHEDULED_DAY","targetCount":20},"tracking":{"mode":"QUANTITY","unit":"words","defaultIncrement":1},"reminder":{"localTime":"08:30","isEnabled":true}}""",
            ).objectValue()
            assertTrue(habitResult.boolean("ok"))
            val habit = database.habits.list().single()
            assertEquals("Read", habit.name)
            assertEquals(31, habit.schedule.weekdaysMask)
            assertEquals(20, habit.goal.targetCount)
            assertEquals("08:30", habit.reminder?.localTime)

            assertEquals(1, tools.execute("read_tasks", """{"timeRangeDays":30}""").objectValue()["tasks"]!!.jsonArray.size)
            assertEquals(1, tools.execute("read_habits", "{}").objectValue()["habits"]!!.jsonArray.size)

            val update = tools.execute(
                "update_deadline",
                """{"taskId":"$taskUid","newDueTime":"2026-08-22 09:30"}""",
            ).objectValue()
            assertTrue(update.boolean("ok"))
            assertEquals("2026-08-22T09:30", database.tasks.find(taskUid)?.dueAt)
            assertEquals(listOf(taskUid, taskUid), notifications.taskSchedules.map { it.taskUid })
            assertEquals(database.habits.list().single().uid, notifications.habitSchedules.single().habitUid)
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
            val created = result["createdHabits"]!!.jsonArray.single().jsonObject
            assertNotNull(created["compatibilityWarning"]?.jsonPrimitive?.content)
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

private class RecordingNotificationGateway : NotificationGateway {
    val taskSchedules = mutableListOf<TaskNotificationConfig>()
    val habitSchedules = mutableListOf<HabitNotificationConfig>()
    val cancelledHabitUids = mutableListOf<String>()

    override fun setTaskNotification(config: TaskNotificationConfig) { taskSchedules += config }
    override fun cancelTaskNotification(taskUid: String) = Unit
    override fun cancelAllTaskNotifications() = Unit
    override fun setHabitNotification(config: HabitNotificationConfig) { habitSchedules += config }
    override fun cancelHabitNotification(habitUid: String) { cancelledHabitUids += habitUid }
    override fun cancelAllHabitNotifications() = Unit
    override fun setDailySummaryNotification(config: DailySummaryConfig) = Unit
    override fun cancelDailySummaryNotification() = Unit
    override fun setUpcomingLiveUpdate(config: UpcomingLiveUpdateConfig) = Unit
    override fun cancelUpcomingLiveUpdate(taskUid: String) = Unit
    override fun cancelAll() = Unit
    override fun refreshAll(tasks: List<TaskNotificationInfo>, habits: List<HabitNotificationInfo>) = Unit
}
