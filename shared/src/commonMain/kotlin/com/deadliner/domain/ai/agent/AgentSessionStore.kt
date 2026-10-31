package com.deadliner.domain.ai.agent

/** Local runtime state, deliberately excluded from business synchronization. */
interface AgentSessionStore {
    fun readSession(sessionId: String): String?
    fun saveSession(sessionId: String, payload: String)
}
