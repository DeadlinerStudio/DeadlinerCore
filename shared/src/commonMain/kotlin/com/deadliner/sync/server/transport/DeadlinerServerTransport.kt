package com.deadliner.sync.server.transport

import com.deadliner.sync.server.model.ServerSyncPullResponse
import com.deadliner.sync.server.model.ServerSyncPushRequest
import com.deadliner.sync.server.model.ServerSyncPushResponse

/** Provider-neutral boundary used by the Core sync engine. */
interface DeadlinerServerTransport : AutoCloseable {
    suspend fun push(request: ServerSyncPushRequest): ServerSyncPushResponse

    suspend fun pull(cursor: Long, limit: Int): ServerSyncPullResponse

    override fun close() = Unit
}
