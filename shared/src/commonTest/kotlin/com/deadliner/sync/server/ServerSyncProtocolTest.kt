package com.deadliner.sync.server

import com.deadliner.sync.server.model.ServerSyncAction
import com.deadliner.sync.server.model.ServerSyncClientVersion
import com.deadliner.sync.server.model.ServerSyncEntityKind
import com.deadliner.sync.server.model.ServerSyncMutation
import com.deadliner.sync.server.model.ServerSyncPushRequest
import com.deadliner.sync.server.transport.DeadlinerServerTransportConfiguration
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerSyncProtocolTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun pushContractUsesStableSnakeCaseFieldsAndRoundTripsOpaquePayload() {
        val request = ServerSyncPushRequest(
            deviceUid = "device-a",
            baseCursor = 41,
            mutations = listOf(
                ServerSyncMutation(
                    mutationId = "mutation-1",
                    entityKind = ServerSyncEntityKind.TASK,
                    entityUid = "task-1",
                    action = ServerSyncAction.UPSERT,
                    baseChangeId = 40,
                    clientVersion = ServerSyncClientVersion("2026-08-30T10:00:00Z", 2, "device-a"),
                    payload = buildJsonObject { put("title", "Offline task") },
                ),
            ),
        )

        val encoded = json.encodeToString(request)

        assertTrue("\"device_uid\":\"device-a\"" in encoded)
        assertTrue("\"base_change_id\":40" in encoded)
        assertTrue("\"action\":\"upsert\"" in encoded)
        assertEquals(request, json.decodeFromString<ServerSyncPushRequest>(encoded))
    }

    @Test
    fun protocolKeepsSnapshotKindsExplicit() {
        assertEquals(
            setOf("category", "task", "focus_session", "habit", "capture", "memory_fragment", "memory_user_profile", "user_profile"),
            ServerSyncEntityKind.snapshotV5Kinds,
        )
    }

    @Test
    fun transportConfigurationRejectsUnsafeOrIncompleteValues() {
        assertFailsWith<IllegalArgumentException> {
            DeadlinerServerTransportConfiguration("server.example.com", "token", "device")
        }
        assertFailsWith<IllegalArgumentException> {
            DeadlinerServerTransportConfiguration("https://server.example.com", " ", "device")
        }
        assertFailsWith<IllegalArgumentException> {
            DeadlinerServerTransportConfiguration("https://server.example.com", "token", " ")
        }
    }
}
