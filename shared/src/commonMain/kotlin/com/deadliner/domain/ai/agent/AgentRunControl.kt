package com.deadliner.domain.ai.agent

interface AgentRunControl : AutoCloseable {
    fun cancel()
}

interface AgentRecoveryContext {
    fun restoreReceipts(receipts: List<AgentActionReceipt>)
}
interface AgentProgressSource {
    fun setProgressListener(listener: suspend (String) -> Unit)
}
