package com.deadliner.db.repository

import com.deadliner.db.AppDatabase
import com.deadliner.domain.memory.MemoryFragment
import com.deadliner.model.ChangeOperation
import com.deadliner.sync.RepositoryMutationNotifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class MemoryRepository(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
    private val mutationNotifier: RepositoryMutationNotifier = RepositoryMutationNotifier.NONE,
) : com.deadliner.domain.ai.agent.AgentSessionStore {
    fun activeLifiSessionId(): String = readSession("__active_lifi_session__")
        ?.takeIf { it.isNotBlank() } ?: startNewLifiSession()

    fun startNewLifiSession(): String = com.deadliner.platform.PlatformIdentity.newId().also {
        saveSession("__active_lifi_session__", it)
    }

    override fun readSession(sessionId: String): String? =
        database.aiSessionQueries.selectSession(sessionId).executeAsOneOrNull()

    override fun saveSession(sessionId: String, payload: String) {
        database.aiSessionQueries.saveSession(sessionId, payload)
    }
    fun list(): List<MemoryFragment> = database.memoryQueries.selectAll(::toMemory).executeAsList()

    fun find(uid: String): MemoryFragment? =
        database.memoryQueries.selectByUid(uid, ::toMemory).executeAsOneOrNull()

    fun create(fragment: MemoryFragment) = save(fragment, ChangeOperation.INSERT)

    fun update(fragment: MemoryFragment) = save(fragment, ChangeOperation.UPDATE)

    fun getUserProfile(): String =
        database.memoryUserProfileQueries.selectProfile().executeAsOneOrNull().orEmpty()

    fun updateUserProfile(profile: String) {
        val normalized = profile.trim()
        database.transaction {
            database.memoryUserProfileQueries.upsertProfile(normalized, currentTimestamp())
        }
        mutationNotifier.notifyMutation()
    }

    fun delete(uid: String, updatedAt: String) {
        database.transaction {
            database.memoryQueries.softDeleteMemory(updatedAt, uid)
            changeLog.appendLocal("memory_fragment", uid, ChangeOperation.DELETE, null)
        }
        mutationNotifier.notifyMutation()
    }

    fun replaceSnapshot(fragments: List<MemoryFragment>, userProfile: String?) {
        database.transaction {
            val existing = list()
            for (old in existing) {
                database.memoryQueries.softDeleteMemory(currentTimestamp(), old.uid)
            }
            for (fragment in fragments) {
                database.memoryQueries.insertMemory(
                    fragment.uid, fragment.content, fragment.category,
                    fragment.createdAt, fragment.importance.toLong(),
                    fragment.updatedAt, fragment.isDeleted.asLong(),
                )
                database.memoryQueries.updateMemory(
                    fragment.content, fragment.category, fragment.importance.toLong(),
                    fragment.updatedAt, fragment.isDeleted.asLong(), fragment.uid,
                )
            }
            if (userProfile == null) {
                database.memoryUserProfileQueries.deleteProfile()
            } else {
                database.memoryUserProfileQueries.upsertProfile(userProfile.trim(), currentTimestamp())
            }
        }
        mutationNotifier.notifyMutation()
    }

    fun appendConversationTurn(role: String, content: String) {
        val truncated = content.take(MAX_CONVERSATION_MESSAGE_CHARS)
        database.conversationTurnQueries.insertTurn(role, truncated, currentTimestamp())
        pruneConversationHistory()
    }

    fun getRecentConversationContext(limit: Int): String {
        val turns = database.conversationTurnQueries
            .selectRecent(limit.toLong())
            .executeAsList()
        if (turns.isEmpty()) return ""
        return turns.reversed().joinToString("\n") { (role, content, _) ->
            "[$role] $content"
        }
    }

    private fun pruneConversationHistory() {
        val count = database.conversationTurnQueries
            .selectRecent(MAX_CONVERSATION_TURNS.toLong() + 100)
            .executeAsList()
            .size
        if (count > MAX_CONVERSATION_TURNS) {
            val cutoff = database.conversationTurnQueries
                .selectRecent(MAX_CONVERSATION_TURNS.toLong())
                .executeAsList()
                .lastOrNull()?.created_at ?: return
            database.conversationTurnQueries.deleteOlderThan(cutoff)
        }
    }

    private fun save(fragment: MemoryFragment, operation: ChangeOperation) {
        database.transaction {
            database.memoryQueries.insertMemory(
                fragment.uid, fragment.content, fragment.category, fragment.createdAt, fragment.importance.toLong(),
                fragment.updatedAt, fragment.isDeleted.asLong(),
            )
            database.memoryQueries.updateMemory(
                fragment.content, fragment.category, fragment.importance.toLong(), fragment.updatedAt,
                fragment.isDeleted.asLong(), fragment.uid,
            )
            changeLog.appendLocal("memory_fragment", fragment.uid, operation, json.encodeToJsonElement(fragment).jsonObject)
        }
        mutationNotifier.notifyMutation()
    }

    private fun toMemory(
        uid: String, content: String, category: String, createdAt: String, importance: Long, updatedAt: String, isDeleted: Long,
    ) = MemoryFragment(uid, content, category, createdAt, importance.toInt(), updatedAt, isDeleted.asBoolean())

    companion object {
        private const val MAX_CONVERSATION_TURNS = 24
        private const val MAX_CONVERSATION_MESSAGE_CHARS = 400
    }
}

private fun currentTimestamp(): String =
    kotlinx.datetime.Clock.System.now().toString()
