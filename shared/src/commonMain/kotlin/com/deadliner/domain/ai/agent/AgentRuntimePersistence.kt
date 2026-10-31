package com.deadliner.domain.ai.agent

interface AgentRuntimePersistence {
    val runtimeSessionId: String? get() = null
    fun readV2State(): String? = null
    fun saveV2State(payload: String) {}
    fun readRuntimeState(): String?
    fun saveRuntimeState(payload: String)
}
