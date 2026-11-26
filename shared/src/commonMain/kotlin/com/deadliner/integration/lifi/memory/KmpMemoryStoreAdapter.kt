package com.deadliner.lifi.memory

import com.deadliner.domain.ai.port.MemoryStorePort
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.domain.memory.MemorySnapshot
import com.deadliner.db.repository.MemoryRepository
import kotlinx.datetime.Clock

class KmpMemoryStoreAdapter(
    private val memoryRepo: MemoryRepository,
) : MemoryStorePort, com.deadliner.domain.ai.agent.AgentSessionStore {
    fun activeSessionId(): String = memoryRepo.activeLifiSessionId()
    override fun readSession(sessionId: String): String? = memoryRepo.readSession(sessionId)
    override fun saveSession(sessionId: String, payload: String) = memoryRepo.saveSession(sessionId, payload)

    override suspend fun saveMemory(content: String, category: String) {
        val now = currentTimestamp()
        memoryRepo.create(
            MemoryFragment(
                uid = generateUid(),
                content = content,
                category = category,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    override suspend fun getAllFragments(): List<MemoryFragment> {
        return memoryRepo.list()
    }

    override suspend fun saveUserProfile(profile: String) {
        memoryRepo.updateUserProfile(profile)
    }

    override suspend fun getUserProfile(): String {
        return memoryRepo.getUserProfile()
    }

    override suspend fun getLongTermContext(): String {
        val profile = getUserProfile()
        val fragments = getAllFragments()
            .sortedByDescending { it.importance }
            .take(6)
        val memoryText = fragments.joinToString("\n") { it.content }
        val context = buildString {
            if (profile.isNotBlank()) append("用户画像: $profile\n")
            if (memoryText.isNotBlank()) append("近期偏好: $memoryText")
        }
        return context.take(900)
    }

    override suspend fun replaceSnapshot(snapshot: MemorySnapshot) {
        memoryRepo.replaceSnapshot(snapshot.fragments, snapshot.userProfile)
    }

    override suspend fun currentSnapshot(): MemorySnapshot {
        val fragments = getAllFragments()
        val profile = getUserProfile()
        return MemorySnapshot(
            revision = Clock.System.now().toEpochMilliseconds(),
            fragments = fragments,
            userProfile = profile,
        )
    }

    override suspend fun appendConversationTurn(role: String, content: String) {
        memoryRepo.appendConversationTurn(role, content)
    }

    override suspend fun getRecentConversationContext(limit: Int): String {
        return memoryRepo.getRecentConversationContext(limit)
    }

    private fun currentTimestamp(): String =
        kotlinx.datetime.Clock.System.now().toString()

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    private fun generateUid(): String =
        kotlin.uuid.Uuid.random().toString()
}
