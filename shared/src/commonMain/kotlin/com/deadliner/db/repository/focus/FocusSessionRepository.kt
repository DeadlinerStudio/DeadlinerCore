package com.deadliner.db.repository.focus

import com.deadliner.db.AppDatabase
import com.deadliner.db.repository.ChangeLogRepository
import com.deadliner.db.repository.asBoolean
import com.deadliner.db.repository.asLong
import com.deadliner.domain.focus.FocusCompletionKind
import com.deadliner.domain.focus.FocusTargetRef
import com.deadliner.domain.focus.FocusTargetKind
import com.deadliner.domain.focus.FocusSession
import com.deadliner.domain.focus.FocusTimerMode
import com.deadliner.domain.focus.FocusTimerOutcome
import com.deadliner.domain.focus.MAX_ADJUSTED_DURATION_SECONDS
import com.deadliner.model.ChangeOperation
import com.deadliner.sync.RepositoryMutationNotifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.datetime.Instant

class FocusSessionRepository(
    private val database: AppDatabase,
    private val changeLog: ChangeLogRepository,
    private val json: Json = Json,
    private val mutationNotifier: RepositoryMutationNotifier = RepositoryMutationNotifier.NONE,
) {
    fun list(): List<FocusSession> = database.focusSessionQueries.selectAll(::toSession).executeAsList()

    /** Keep the latest tombstone so deleting a result cannot expose an older completion. */
    fun latestForNotification(): FocusSession? = database.focusSessionQueries.selectLatestForNotification(::toSession).executeAsOneOrNull()

    fun find(uid: String): FocusSession? =
        database.focusSessionQueries.selectByUid(uid, ::toSession).executeAsOneOrNull()

    fun forTask(taskUid: String): List<FocusSession> =
        database.focusSessionQueries.selectForTask(taskUid, ::toSession).executeAsList()

    fun recentForTask(taskUid: String, limit: Int): List<FocusSession> {
        if (limit <= 0) return emptyList()
        return database.focusSessionQueries
            .selectRecentForTask(taskUid, limit.toLong(), ::toSession)
            .executeAsList()
    }

    fun forTarget(target: FocusTargetRef): List<FocusSession> =
        database.focusSessionQueries.selectForTarget(target.kind.name, target.uid, ::toSession).executeAsList()

    fun recent(limit: Int): List<FocusSession> {
        if (limit <= 0) return emptyList()
        return database.focusSessionQueries.selectRecent(limit.toLong(), ::toSession).executeAsList()
    }

    fun totalForTask(taskUid: String): Long =
        database.focusSessionQueries.sumDurationForTask(taskUid).executeAsOne()

    fun totalStartedInRange(startInclusive: String, endExclusive: String): Long {
        requirePositiveRange(startInclusive, endExclusive)
        return database.focusSessionQueries
            .sumDurationStartedInRange(startInclusive, endExclusive)
            .executeAsOne()
    }

    fun countStartedInRange(startInclusive: String, endExclusive: String): Long {
        requirePositiveRange(startInclusive, endExclusive)
        return database.focusSessionQueries
            .countStartedInRange(startInclusive, endExclusive)
            .executeAsOne()
    }

    fun totalForTaskStartedInRange(
        taskUid: String,
        startInclusive: String,
        endExclusive: String,
    ): Long {
        requirePositiveRange(startInclusive, endExclusive)
        return database.focusSessionQueries
            .sumDurationForTaskStartedInRange(taskUid, startInclusive, endExclusive)
            .executeAsOne()
    }

    fun adjustDuration(uid: String, durationSeconds: Long, updatedAt: String): FocusSessionMutationResult {
        val existing = find(uid)?.takeUnless(FocusSession::isDeleted)
            ?: return FocusSessionMutationResult(null, FocusTimerOutcome.TIMER_NOT_FOUND)
        val wallClockSpan = (Instant.parse(existing.endedAt) - Instant.parse(existing.startedAt)).inWholeSeconds
        val maximum = minOf(existing.durationSeconds, wallClockSpan, MAX_ADJUSTED_DURATION_SECONDS)
        if (durationSeconds !in 1..maximum) {
            return FocusSessionMutationResult(existing, FocusTimerOutcome.INVALID_DURATION)
        }
        if (durationSeconds == existing.durationSeconds) {
            return FocusSessionMutationResult(existing, FocusTimerOutcome.NO_OP)
        }
        val updated = existing.copy(durationSeconds = durationSeconds, updatedAt = updatedAt)
        database.transaction { saveInTransaction(updated, ChangeOperation.UPDATE) }
        mutationNotifier.notifyMutation()
        return FocusSessionMutationResult(updated, FocusTimerOutcome.APPLIED)
    }

    fun delete(uid: String, updatedAt: String): FocusSessionMutationResult {
        val existing = find(uid)?.takeUnless(FocusSession::isDeleted)
            ?: return FocusSessionMutationResult(null, FocusTimerOutcome.TIMER_NOT_FOUND)
        val deleted = existing.copy(updatedAt = updatedAt, isDeleted = true)
        database.transaction {
            database.focusSessionQueries.softDeleteSession(updatedAt, uid)
            changeLog.appendLocal(ENTITY_NAME, uid, ChangeOperation.DELETE, null)
        }
        mutationNotifier.notifyMutation()
        return FocusSessionMutationResult(deleted, FocusTimerOutcome.APPLIED)
    }

    internal fun saveInTransaction(session: FocusSession, operation: ChangeOperation) {
        database.focusSessionQueries.insertSession(
            session.uid,
            session.taskUid,
            session.taskTitleSnapshot,
            session.mode.name,
            session.plannedDurationSeconds,
            session.startedAt,
            session.endedAt,
            session.durationSeconds,
            session.completionKind.name,
            session.createdAt,
            session.updatedAt,
            session.isDeleted.asLong(),
            session.boundTarget.kind.name,
            session.target?.takeIf { it.kind == FocusTargetKind.HABIT }?.uid,
            session.habitOccurredOn,
            session.habitRecordedAmount?.toLong(),
        )
        database.focusSessionQueries.updateSession(
            session.taskUid,
            session.taskTitleSnapshot,
            session.mode.name,
            session.plannedDurationSeconds,
            session.startedAt,
            session.endedAt,
            session.durationSeconds,
            session.completionKind.name,
            session.updatedAt,
            session.isDeleted.asLong(),
            session.boundTarget.kind.name,
            session.target?.takeIf { it.kind == FocusTargetKind.HABIT }?.uid,
            session.habitOccurredOn,
            session.habitRecordedAmount?.toLong(),
            session.uid,
        )
        changeLog.appendLocal(
            ENTITY_NAME,
            session.uid,
            operation,
            json.encodeToJsonElement(session).jsonObject,
        )
    }

    private fun toSession(
        uid: String,
        taskUid: String,
        taskTitleSnapshot: String,
        mode: String,
        plannedDurationSeconds: Long?,
        startedAt: String,
        endedAt: String,
        durationSeconds: Long,
        completionKind: String,
        createdAt: String,
        updatedAt: String,
        isDeleted: Long,
        targetKind: String,
        habitUid: String?,
        habitOccurredOn: String?,
        habitRecordedAmount: Long?,
    ) = FocusSession(
        uid = uid,
        taskUid = taskUid,
        taskTitleSnapshot = taskTitleSnapshot,
        mode = FocusTimerMode.valueOf(mode),
        plannedDurationSeconds = plannedDurationSeconds,
        startedAt = startedAt,
        endedAt = endedAt,
        durationSeconds = durationSeconds,
        completionKind = FocusCompletionKind.valueOf(completionKind),
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDeleted = isDeleted.asBoolean(),
        target = if (targetKind == "HABIT") FocusTargetRef(FocusTargetKind.HABIT, requireNotNull(habitUid)) else null,
        habitOccurredOn = habitOccurredOn,
        habitRecordedAmount = habitRecordedAmount?.toInt(),
    )

    private fun requirePositiveRange(startInclusive: String, endExclusive: String) {
        require(Instant.parse(startInclusive) < Instant.parse(endExclusive)) {
            "Focus duration range must have a positive span"
        }
    }

    companion object {
        const val ENTITY_NAME = "focus_session"
    }
}

data class FocusSessionMutationResult(
    val session: FocusSession?,
    val outcome: FocusTimerOutcome,
)
