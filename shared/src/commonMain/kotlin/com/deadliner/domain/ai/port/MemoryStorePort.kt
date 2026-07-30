package com.deadliner.domain.ai.port

import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.memory.MemorySnapshot

interface MemoryStorePort {
    suspend fun saveMemory(content: String, category: String)
    suspend fun getAllFragments(): List<MemoryFragment>
    suspend fun saveUserProfile(profile: String)
    suspend fun getUserProfile(): String
    suspend fun getLongTermContext(): String
    suspend fun replaceSnapshot(snapshot: MemorySnapshot)
    suspend fun currentSnapshot(): MemorySnapshot
    suspend fun appendConversationTurn(role: String, content: String)
    suspend fun getRecentConversationContext(limit: Int): String
}
