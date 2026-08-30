package com.deadliner.sync.server.engine

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.account.UserProfile
import com.deadliner.domain.capture.CaptureItem
import com.deadliner.domain.capture.CaptureSource
import com.deadliner.domain.category.Category
import com.deadliner.domain.habit.Habit
import com.deadliner.domain.habit.HabitPeriod
import com.deadliner.domain.habit.HabitRecord
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.task.Task
import com.deadliner.domain.task.TaskSubtask
import com.deadliner.sync.server.model.ServerSyncAction
import com.deadliner.sync.server.model.ServerSyncChange
import com.deadliner.sync.server.model.ServerSyncClientVersion
import com.deadliner.sync.server.model.ServerSyncMutation
import com.deadliner.sync.server.model.ServerSyncMutationResult
import com.deadliner.sync.server.model.ServerSyncMutationStatus
import com.deadliner.sync.server.model.ServerSyncPullResponse
import com.deadliner.sync.server.model.ServerSyncPushRequest
import com.deadliner.sync.server.model.ServerSyncPushResponse
import com.deadliner.sync.server.model.ServerSyncServerVersion
import com.deadliner.sync.server.transport.DeadlinerServerTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeadlinerServerSyncEngineIntegrationTest {
    @Test
    fun durableOutboxSurvivesPullFailureAndKeepsMutationIdsAfterReopen() = runBlocking {
        val path = temporaryDatabasePath("durable-outbox")
        val server = FakeSyncServer()
        var persistedMutationIds: Set<String> = emptySet()
        val first = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, DEVICE_A))
        try {
            first.tasks.create(task("task.offline", "离线任务", "2026-08-30T01:00:00Z"))
            val failingTransport = server.transport(DEVICE_A, failPulls = 1)
            val engine = first.createDeadlinerServerSyncEngine(configuration(DEVICE_A), failingTransport)

            assertFailsWith<IllegalStateException> { engine.synchronize() }
            persistedMutationIds = first.database.serverSyncStateQueries
                .selectAllOutbox(SCOPE_ID) { _, mutationId, _, _, _, _, _, _, _, _, _, _, _ -> mutationId }
                .executeAsList()
                .toSet()
            assertTrue(persistedMutationIds.isNotEmpty())
            engine.close()
        } finally {
            first.close()
        }

        val reopened = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, DEVICE_A))
        try {
            val transport = server.transport(DEVICE_A)
            val outcome = reopened.createDeadlinerServerSyncEngine(configuration(DEVICE_A), transport)
                .use { it.synchronize() }

            assertTrue(outcome.converged)
            assertTrue(persistedMutationIds.all { it in transport.pushedMutationIds })
            assertEquals(0, reopened.database.serverSyncStateQueries.countOutbox(SCOPE_ID).executeAsOne())
            assertNotNull(server.document("task", "task.offline"))
        } finally {
            reopened.close()
            remove(path)
        }
        Unit
    }

    @Test
    fun twoOfflineDevicesConvergeIndependentTasksThroughPaginatedPull() = runBlocking {
        val pathA = temporaryDatabasePath("device-a")
        val pathB = temporaryDatabasePath("device-b")
        val server = FakeSyncServer()
        val deviceA = DeadlinerDatabase.create(MacosDatabaseDriverFactory(pathA, DEVICE_A))
        val deviceB = DeadlinerDatabase.create(MacosDatabaseDriverFactory(pathB, DEVICE_B))
        try {
            deviceA.tasks.create(task("task.a", "A 的离线任务", "2026-08-30T02:00:00Z"))
            deviceB.tasks.create(task("task.b", "B 的离线任务", "2026-08-30T03:00:00Z"))

            deviceA.createDeadlinerServerSyncEngine(configuration(DEVICE_A), server.transport(DEVICE_A))
                .use { assertTrue(it.synchronize().converged) }
            val outcomeB = deviceB.createDeadlinerServerSyncEngine(
                configuration(DEVICE_B, pullPageSize = 1),
                server.transport(DEVICE_B),
            ).use { it.synchronize() }
            val outcomeA = deviceA.createDeadlinerServerSyncEngine(
                configuration(DEVICE_A, pullPageSize = 1),
                server.transport(DEVICE_A),
            ).use { it.synchronize() }

            assertTrue(outcomeB.pullPages > 1)
            assertTrue(outcomeA.pullPages > 0)
            assertEquals("A 的离线任务", deviceB.tasks.find("task.a")?.title)
            assertEquals("B 的离线任务", deviceA.tasks.find("task.b")?.title)
            assertNotNull(server.document("task", "task.a"))
            assertNotNull(server.document("task", "task.b"))
        } finally {
            deviceA.close()
            deviceB.close()
            remove(pathA)
            remove(pathB)
        }
        Unit
    }

    @Test
    fun newerOfflineEditWinsAfterRemoteVersionAdvances() = runBlocking {
        val pathA = temporaryDatabasePath("conflict-a")
        val pathB = temporaryDatabasePath("conflict-b")
        val server = FakeSyncServer()
        val deviceA = DeadlinerDatabase.create(MacosDatabaseDriverFactory(pathA, DEVICE_A))
        val deviceB = DeadlinerDatabase.create(MacosDatabaseDriverFactory(pathB, DEVICE_B))
        try {
            deviceA.tasks.create(task("task.shared", "初始", "2026-08-30T01:00:00Z"))
            deviceA.createDeadlinerServerSyncEngine(configuration(DEVICE_A), server.transport(DEVICE_A))
                .use { it.synchronize() }
            deviceB.createDeadlinerServerSyncEngine(configuration(DEVICE_B), server.transport(DEVICE_B))
                .use { it.synchronize() }

            deviceA.tasks.update(requireNotNull(deviceA.tasks.find("task.shared")).copy(
                title = "A 的编辑",
                updatedAt = "2026-08-30T04:00:00Z",
            ))
            deviceB.tasks.update(requireNotNull(deviceB.tasks.find("task.shared")).copy(
                title = "B 的更新编辑",
                updatedAt = "2026-08-30T05:00:00Z",
            ))
            deviceA.createDeadlinerServerSyncEngine(configuration(DEVICE_A), server.transport(DEVICE_A))
                .use { it.synchronize() }
            val outcomeB = deviceB.createDeadlinerServerSyncEngine(configuration(DEVICE_B), server.transport(DEVICE_B))
                .use { it.synchronize() }

            assertTrue(outcomeB.converged)
            val serverTask = Json.decodeFromJsonElement<Task>(
                requireNotNull(server.document("task", "task.shared")).payload!!,
            )
            assertEquals("B 的更新编辑", serverTask.title)
        } finally {
            deviceA.close()
            deviceB.close()
            remove(pathA)
            remove(pathB)
        }
        Unit
    }

    @Test
    fun concurrentRemoteWriteReturnsConflictAndRetriesLocalWinnerWithNewBase() = runBlocking {
        val path = temporaryDatabasePath("conflict-retry")
        val server = FakeSyncServer()
        val database = DeadlinerDatabase.create(MacosDatabaseDriverFactory(path, DEVICE_A))
        try {
            database.tasks.create(task("task.race", "本地较新", "2026-08-30T06:00:00Z"))
            val transport = server.transport(DEVICE_A) {
                server.seedTask(task("task.race", "远端并发", "2026-08-30T05:00:00Z"), DEVICE_B)
            }

            val outcome = database.createDeadlinerServerSyncEngine(configuration(DEVICE_A), transport)
                .use { it.synchronize() }

            assertTrue(outcome.conflicts > 0)
            assertTrue(outcome.converged)
            val serverTask = Json.decodeFromJsonElement<Task>(
                requireNotNull(server.document("task", "task.race")).payload!!,
            )
            assertEquals("本地较新", serverTask.title)
        } finally {
            database.close()
            remove(path)
        }
        Unit
    }

    @Test
    fun allSnapshotBusinessAggregatesRoundTripThroughServer() = runBlocking {
        val sourcePath = temporaryDatabasePath("all-source")
        val targetPath = temporaryDatabasePath("all-target")
        val server = FakeSyncServer()
        val source = DeadlinerDatabase.create(MacosDatabaseDriverFactory(sourcePath, DEVICE_A))
        val target = DeadlinerDatabase.create(MacosDatabaseDriverFactory(targetPath, DEVICE_B))
        try {
            source.categories.create(Category(
                uid = "category.custom",
                name = "项目",
                iconKey = "folder",
                colorHex = "#3366FF",
                isPreset = false,
                sortOrder = 10,
                createdAt = "2026-08-30T01:00:00Z",
                updatedAt = "2026-08-30T01:00:00Z",
            ))
            source.tasks.create(Task(
                uid = "task.complete-coverage",
                title = "覆盖所有聚合",
                categoryUid = "category.custom",
                subtasks = listOf(TaskSubtask(
                    uid = "subtask.coverage",
                    taskUid = "task.complete-coverage",
                    content = "同步子任务",
                    createdAt = "2026-08-30T02:00:00Z",
                    updatedAt = "2026-08-30T02:00:00Z",
                )),
                createdAt = "2026-08-30T02:00:00Z",
                updatedAt = "2026-08-30T02:00:00Z",
            ))
            source.habits.create(Habit(
                uid = "habit.coverage",
                name = "每日复盘",
                period = HabitPeriod.DAILY,
                createdAt = "2026-08-30T03:00:00Z",
                updatedAt = "2026-08-30T03:00:00Z",
            ))
            source.habits.saveRecord(HabitRecord(
                uid = "record.coverage",
                habitUid = "habit.coverage",
                occurredOn = "2026-08-30",
                createdAt = "2026-08-30T03:30:00Z",
                updatedAt = "2026-08-30T03:30:00Z",
            ))
            source.captures.create(CaptureItem(
                uid = "capture.coverage",
                text = "稍后整理",
                source = CaptureSource.MANUAL,
                createdAt = "2026-08-30T04:00:00Z",
                updatedAt = "2026-08-30T04:00:00Z",
            ))
            source.memory.create(MemoryFragment(
                uid = "memory.coverage",
                content = "偏好简洁回复",
                category = "preference",
                createdAt = "2026-08-30T05:00:00Z",
                updatedAt = "2026-08-30T05:00:00Z",
            ))
            source.memory.updateUserProfile("用户倾向周计划")
            source.profiles.create(UserProfile(
                uid = "profile.coverage",
                nickname = "Aritx",
                createdAt = "2026-08-30T06:00:00Z",
                updatedAt = "2026-08-30T06:00:00Z",
            ))

            source.createDeadlinerServerSyncEngine(configuration(DEVICE_A), server.transport(DEVICE_A))
                .use { assertTrue(it.synchronize().converged) }
            target.createDeadlinerServerSyncEngine(configuration(DEVICE_B), server.transport(DEVICE_B))
                .use { assertTrue(it.synchronize().converged) }

            assertEquals("项目", target.categories.find("category.custom")?.name)
            assertEquals("同步子任务", target.tasks.find("task.complete-coverage")?.subtasks?.single()?.content)
            assertEquals("每日复盘", target.habits.find("habit.coverage")?.name)
            assertEquals("record.coverage", target.habits.records("habit.coverage").single().uid)
            assertEquals("稍后整理", target.captures.find("capture.coverage")?.text)
            assertEquals("偏好简洁回复", target.memory.find("memory.coverage")?.content)
            assertEquals("用户倾向周计划", target.memory.getUserProfile())
            assertEquals("Aritx", target.profiles.find("profile.coverage")?.nickname)
        } finally {
            source.close()
            target.close()
            remove(sourcePath)
            remove(targetPath)
        }
        Unit
    }

    private fun configuration(deviceUid: String, pullPageSize: Int = 100) =
        DeadlinerServerSyncConfiguration(
            scopeId = SCOPE_ID,
            deviceUid = deviceUid,
            pullPageSize = pullPageSize,
        )

    private fun task(uid: String, title: String, updatedAt: String) = Task(
        uid = uid,
        title = title,
        createdAt = "2026-08-30T00:00:00Z",
        updatedAt = updatedAt,
    )

    private fun temporaryDatabasePath(label: String) =
        "${NSTemporaryDirectory()}deadliner-byos-$label-${NSUUID().UUIDString}.db"

    private companion object {
        const val SCOPE_ID = "https://server.example/account-1"
        const val DEVICE_A = "device-a"
        const val DEVICE_B = "device-b"
    }
}

private class FakeSyncServer {
    private val changes = mutableListOf<ServerSyncChange>()
    private val documents = mutableMapOf<Pair<String, String>, ServerSyncChange>()
    private val receipts = mutableMapOf<Pair<String, String>, ServerSyncMutationResult>()

    fun transport(
        deviceUid: String,
        failPulls: Int = 0,
        beforeFirstPush: (() -> Unit)? = null,
    ) = FakeSyncTransport(this, deviceUid, failPulls, beforeFirstPush)

    fun document(kind: String, uid: String): ServerSyncChange? = documents[kind to uid]

    fun seedTask(task: Task, deviceUid: String) {
        val mutation = ServerSyncMutation(
            mutationId = "seed-${task.uid}-${changes.size}",
            entityKind = "task",
            entityUid = task.uid,
            action = ServerSyncAction.UPSERT,
            baseChangeId = documents["task" to task.uid]?.serverVersion?.changeId ?: 0,
            clientVersion = ServerSyncClientVersion(task.updatedAt, 0, deviceUid),
            payload = Json.encodeToJsonElement(task),
        )
        push(deviceUid, ServerSyncPushRequest(deviceUid, 0, listOf(mutation)))
    }

    fun push(deviceUid: String, request: ServerSyncPushRequest): ServerSyncPushResponse {
        val results = request.mutations.map { mutation ->
            val receiptKey = deviceUid to mutation.mutationId
            receipts[receiptKey]?.let { saved ->
                return@map if (saved.status == ServerSyncMutationStatus.APPLIED) {
                    saved.copy(status = ServerSyncMutationStatus.REPLAYED)
                } else {
                    saved
                }
            }
            val key = mutation.entityKind to mutation.entityUid
            val latest = documents[key]
            val currentChangeId = latest?.serverVersion?.changeId ?: 0
            val result = if (mutation.baseChangeId != currentChangeId) {
                ServerSyncMutationResult(
                    mutationId = mutation.mutationId,
                    status = ServerSyncMutationStatus.CONFLICT,
                    latest = latest,
                )
            } else {
                val changeId = changes.size.toLong() + 1
                val change = mutation.toChange(changeId, deviceUid)
                changes += change
                documents[key] = change
                ServerSyncMutationResult(
                    mutationId = mutation.mutationId,
                    status = ServerSyncMutationStatus.APPLIED,
                    change = change,
                )
            }
            receipts[receiptKey] = result
            result
        }
        return ServerSyncPushResponse(results, request.baseCursor)
    }

    fun pull(cursor: Long, limit: Int): ServerSyncPullResponse {
        val available = changes.filter { it.serverVersion.changeId > cursor }
        val page = available.take(limit)
        return ServerSyncPullResponse(
            changes = page,
            nextCursor = page.lastOrNull()?.serverVersion?.changeId ?: cursor,
            hasMore = available.size > page.size,
        )
    }

    private fun ServerSyncMutation.toChange(changeId: Long, deviceUid: String) = ServerSyncChange(
        entityKind = entityKind,
        entityUid = entityUid,
        action = action,
        serverVersion = ServerSyncServerVersion(
            changeId = changeId,
            committedAt = "2026-08-30T00:00:00.${changeId.toString().padStart(3, '0')}Z",
        ),
        clientVersion = clientVersion,
        updatedByDeviceUid = deviceUid,
        payload = if (action == ServerSyncAction.DELETE) null else payload,
    )
}

private class FakeSyncTransport(
    private val server: FakeSyncServer,
    private val deviceUid: String,
    private var remainingPullFailures: Int,
    private var beforeFirstPush: (() -> Unit)?,
) : DeadlinerServerTransport {
    val pushedMutationIds = mutableListOf<String>()

    override suspend fun push(request: ServerSyncPushRequest): ServerSyncPushResponse {
        beforeFirstPush?.also { callback ->
            beforeFirstPush = null
            callback()
        }
        pushedMutationIds += request.mutations.map(ServerSyncMutation::mutationId)
        return server.push(deviceUid, request)
    }

    override suspend fun pull(cursor: Long, limit: Int): ServerSyncPullResponse {
        if (remainingPullFailures > 0) {
            remainingPullFailures--
            error("offline")
        }
        return server.pull(cursor, limit)
    }
}
