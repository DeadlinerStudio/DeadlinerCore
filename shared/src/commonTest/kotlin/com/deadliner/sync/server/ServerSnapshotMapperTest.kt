package com.deadliner.sync.server

import com.deadliner.domain.focus.FocusCompletionKind
import com.deadliner.domain.focus.FocusSession
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.task.Task
import com.deadliner.sync.server.mapping.ServerSnapshotMapper
import com.deadliner.sync.server.mapping.ServerSyncEntityKey
import com.deadliner.sync.server.model.ServerSyncAction
import com.deadliner.sync.server.model.ServerSyncChange
import com.deadliner.sync.server.model.ServerSyncClientVersion
import com.deadliner.sync.server.model.ServerSyncEntityKind
import com.deadliner.sync.server.model.ServerSyncServerVersion
import com.deadliner.sync.snapshot.SnapshotV3EntityVersion
import com.deadliner.sync.snapshot.SnapshotV3FocusSessionItem
import com.deadliner.sync.snapshot.SnapshotV3Root
import com.deadliner.sync.snapshot.SnapshotV3TaskItem
import com.deadliner.sync.snapshot.SnapshotV3Version
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerSnapshotMapperTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val mapper = ServerSnapshotMapper(json)
    private val version = SnapshotV3EntityVersion("2026-08-30T10:00:00Z", 2, "device-a")
    private val task = Task(
        uid = "task-1",
        title = "Offline task",
        createdAt = "2026-08-30T09:00:00Z",
        updatedAt = version.ts,
    )

    @Test
    fun snapshotTaskBecomesAggregateMutationWithStableBase() {
        val key = ServerSyncEntityKey(ServerSyncEntityKind.TASK, task.uid)
        val mutations = mapper.mutations(
            snapshot = SnapshotV3Root(
                version = SnapshotV3Version(version.ts, version.dev),
                tasks = listOf(SnapshotV3TaskItem(task.uid, version, false, task)),
            ),
            baseChangeIds = mapOf(key to 41),
            mutationId = { entity, logical -> "${entity.kind}:${entity.uid}:${logical.ctr}" },
        )

        val mutation = mutations.single()
        assertEquals("task:task-1:2", mutation.mutationId)
        assertEquals(41, mutation.baseChangeId)
        assertEquals(ServerSyncAction.UPSERT, mutation.action)
        assertEquals("Offline task", mutation.payload?.let { json.decodeFromJsonElement<Task>(it).title })
    }

    @Test
    fun serverDeleteBecomesSnapshotTombstone() {
        val change = change(action = ServerSyncAction.DELETE, payloadTask = null)

        val item = mapper.snapshot(listOf(change)).tasks.single()

        assertEquals(task.uid, item.uid)
        assertEquals(version, item.ver)
        assertEquals(true, item.deleted)
        assertNull(item.doc)
    }

    @Test
    fun serverEnvelopeCannotSmuggleADifferentPayloadUid() {
        val change = change(
            action = ServerSyncAction.UPSERT,
            payloadTask = task.copy(uid = "other-task"),
        )

        assertFailsWith<IllegalArgumentException> { mapper.snapshot(listOf(change)) }
    }

    @Test
    fun focusSessionRoundTripsThroughGenericServerEnvelope() {
        val session = FocusSession(
            uid = "focus-1",
            taskUid = task.uid,
            taskTitleSnapshot = task.title,
            mode = FocusTimerMode.STOPWATCH,
            startedAt = "2026-08-30T09:00:00Z",
            endedAt = "2026-08-30T09:20:00Z",
            durationSeconds = 1_200,
            completionKind = FocusCompletionKind.FOCUS_ONLY,
            createdAt = "2026-08-30T09:20:00Z",
            updatedAt = version.ts,
        )
        val key = ServerSyncEntityKey(ServerSyncEntityKind.FOCUS_SESSION, session.uid)
        val mutation = mapper.mutations(
            SnapshotV3Root(
                version = SnapshotV3Version(version.ts, version.dev),
                focusSessions = listOf(SnapshotV3FocusSessionItem(session.uid, version, false, session)),
            ),
            baseChangeIds = emptyMap(),
            mutationId = { entity, _ -> "${entity.kind}:${entity.uid}" },
        ).single()

        val restored = mapper.snapshot(
            listOf(
                ServerSyncChange(
                    entityKind = mutation.entityKind,
                    entityUid = mutation.entityUid,
                    action = mutation.action,
                    serverVersion = ServerSyncServerVersion(43, "2026-08-30T10:02:00Z"),
                    clientVersion = mutation.clientVersion,
                    payload = mutation.payload,
                ),
            ),
        ).focusSessions.single()

        assertEquals(key.kind, mutation.entityKind)
        assertEquals(session, restored.doc)
    }

    private fun change(action: ServerSyncAction, payloadTask: Task?) = ServerSyncChange(
        entityKind = ServerSyncEntityKind.TASK,
        entityUid = task.uid,
        action = action,
        serverVersion = ServerSyncServerVersion(42, "2026-08-30T10:01:00Z"),
        clientVersion = ServerSyncClientVersion(version.ts, version.ctr, version.dev),
        updatedByDeviceUid = "device-a",
        payload = payloadTask?.let(json::encodeToJsonElement),
    )
}
