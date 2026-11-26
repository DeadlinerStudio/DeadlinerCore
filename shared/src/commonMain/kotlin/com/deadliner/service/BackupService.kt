package com.deadliner.service

import com.deadliner.db.AppDatabase
import com.deadliner.sync.snapshot.SnapshotV3Root
import com.deadliner.sync.snapshot.SnapshotV3Store
import com.deadliner.sync.snapshot.SnapshotV3Validator
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Complete Snapshot V5 backup owned by the shared persistence layer. */
class BackupService internal constructor(private val database: AppDatabase) {
    private val snapshots = SnapshotV3Store(database)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun export(): String = json.encodeToString(
        BackupRoot(
            snapshot = snapshots.export(),
            conversationTurns = database.conversationTurnQueries
                .selectRecent(MAX_CONVERSATION_TURNS)
                .executeAsList()
                .reversed()
                .map { turn -> BackupConversationTurn(turn.role, turn.content, turn.created_at) },
        ),
    )

    /** Invalid files fail with [IllegalArgumentException] (decoding, format or validation); Swift sees `throws`. */
    @Throws(IllegalArgumentException::class)
    fun restore(raw: String) {
        val backup = json.decodeFromString<BackupRoot>(raw)
        require(backup.formatVersion == BackupRoot.FORMAT_VERSION) { "Unsupported backup format" }
        val snapshot = backup.snapshot.upgradeV4()
        SnapshotV3Validator.requireValid(snapshot)
        require(backup.conversationTurns.all { it.role.isNotBlank() && it.createdAt.isNotBlank() }) {
            "Invalid conversation history"
        }
        snapshots.replace(snapshot) {
            database.conversationTurnQueries.deleteAll()
            backup.conversationTurns.forEach { turn ->
                database.conversationTurnQueries.insertTurn(turn.role, turn.content, turn.createdAt)
            }
        }
    }

    private companion object {
        const val MAX_CONVERSATION_TURNS = 500L
    }

    private fun SnapshotV3Root.upgradeV4(): SnapshotV3Root = when (formatVersion) {
        SnapshotV3Root.FORMAT_VERSION -> this
        4 -> copy(formatVersion = SnapshotV3Root.FORMAT_VERSION, focusSessions = emptyList())
        5 -> {
            require(focusSessions.all { it.doc?.boundTarget?.kind != com.deadliner.domain.focus.FocusTargetKind.HABIT }) { "V5 backup cannot contain Habit focus" }
            copy(formatVersion = SnapshotV3Root.FORMAT_VERSION)
        }
        else -> this
    }
}

@Serializable
private data class BackupRoot(
    val formatVersion: Int = FORMAT_VERSION,
    val snapshot: SnapshotV3Root,
    val conversationTurns: List<BackupConversationTurn> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
private data class BackupConversationTurn(
    val role: String,
    val content: String,
    val createdAt: String,
)
