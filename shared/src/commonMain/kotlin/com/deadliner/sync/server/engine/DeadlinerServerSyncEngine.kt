package com.deadliner.sync.server.engine

import com.deadliner.db.AppDatabase
import com.deadliner.sync.server.mapping.ServerSnapshotMapper
import com.deadliner.sync.server.model.ServerSyncChange
import com.deadliner.sync.server.model.ServerSyncMutation
import com.deadliner.sync.server.model.ServerSyncMutationResult
import com.deadliner.sync.server.model.ServerSyncMutationStatus
import com.deadliner.sync.server.model.ServerSyncPushRequest
import com.deadliner.sync.server.state.ServerSyncPendingMutation
import com.deadliner.sync.server.state.ServerSyncStateStore
import com.deadliner.sync.server.transport.DeadlinerServerTransport
import com.deadliner.sync.snapshot.SnapshotV3Store
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.deadliner.platform.WidgetUpdateReason
import com.deadliner.platform.updateWidget
import kotlinx.serialization.json.Json
import kotlin.random.Random

data class DeadlinerServerSyncConfiguration(
    /** Stable opaque identifier for one server + account pair. It must not contain a token. */
    val scopeId: String,
    val deviceUid: String,
    val pullPageSize: Int = DEFAULT_PULL_PAGE_SIZE,
    val pushBatchSize: Int = DEFAULT_PUSH_BATCH_SIZE,
    val maxPullPages: Int = DEFAULT_MAX_PULL_PAGES,
    val maxPushRounds: Int = DEFAULT_MAX_PUSH_ROUNDS,
) {
    init {
        require(scopeId.isNotBlank() && scopeId.length <= 128) {
            "Server sync scope ID must contain 1..128 characters."
        }
        require(deviceUid.isNotBlank() && deviceUid.length <= 128) {
            "Server sync device UID must contain 1..128 characters."
        }
        require(pullPageSize in 1..MAX_PULL_PAGE_SIZE) {
            "Server sync pull page size must be between 1 and $MAX_PULL_PAGE_SIZE."
        }
        require(pushBatchSize in 1..MAX_PUSH_BATCH_SIZE) {
            "Server sync push batch size must be between 1 and $MAX_PUSH_BATCH_SIZE."
        }
        require(maxPullPages in 1..MAX_PULL_PAGES_PER_RUN) {
            "Server sync max pull pages must be between 1 and $MAX_PULL_PAGES_PER_RUN."
        }
        require(maxPushRounds in 1..MAX_PUSH_ROUNDS_PER_RUN) {
            "Server sync max push rounds must be between 1 and $MAX_PUSH_ROUNDS_PER_RUN."
        }
    }

    companion object {
        const val DEFAULT_PULL_PAGE_SIZE = 100
        const val DEFAULT_PUSH_BATCH_SIZE = 100
        const val DEFAULT_MAX_PULL_PAGES = 100
        const val DEFAULT_MAX_PUSH_ROUNDS = 3
        const val MAX_PULL_PAGE_SIZE = 500
        const val MAX_PUSH_BATCH_SIZE = 100
        const val MAX_PULL_PAGES_PER_RUN = 1_000
        const val MAX_PUSH_ROUNDS_PER_RUN = 10
    }
}

data class DeadlinerServerSyncOutcome(
    val pullPages: Int,
    val pulledChanges: Int,
    val remoteApplied: Int,
    val remoteIgnored: Int,
    val pushed: Int,
    val replayed: Int,
    val conflicts: Int,
    val rejected: Int,
    val remainingOutbox: Long,
) {
    val converged: Boolean get() = remainingOutbox == 0L && rejected == 0
}

/**
 * Offline-first BYOS orchestration. Every pull page advances its cursor in the same SQLite
 * transaction as Snapshot V4 merge, and every push is backed by a durable outbox row.
 */
class DeadlinerServerSyncEngine internal constructor(
    database: AppDatabase,
    private val transport: DeadlinerServerTransport,
    private val configuration: DeadlinerServerSyncConfiguration,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
) : AutoCloseable {
    private val mutex = Mutex()
    private val mapper = ServerSnapshotMapper(json)
    private val snapshotStore = SnapshotV3Store(database)
    private val state = ServerSyncStateStore(database, json)

    suspend fun synchronize(): DeadlinerServerSyncOutcome = mutex.withLock {
        val totals = MutableOutcome()
        state.ensureScope(configuration.scopeId)

        // Persist the current local intent before touching the network. If pull fails, the
        // business rows and their retry intent both survive process termination.
        reconcile()
        totals.add(pullAll())

        repeat(configuration.maxPushRounds) {
            val remaining = reconcile()
            if (remaining == 0L) return@withLock totals.finish(0)

            val pushed = pushPending()
            totals.add(pushed)
            totals.add(pullAll())
            if (pushed.rejected > 0) {
                return@withLock totals.finish(reconcile())
            }
        }
        totals.finish(reconcile())
    }.also { updateWidget(WidgetUpdateReason.USER_REFRESH) }

    override fun close() {
        transport.close()
    }

    private fun reconcile(): Long = state.reconcileOutbox(
        scopeId = configuration.scopeId,
        snapshot = snapshotStore.export(),
        mapper = mapper,
        nextMutationId = ::newMutationId,
    )

    private suspend fun pullAll(): PullStats {
        var cursor = state.cursor(configuration.scopeId)
        var pages = 0
        var pulled = 0
        var applied = 0
        var ignored = 0
        while (true) {
            check(pages < configuration.maxPullPages) {
                "Server sync exceeded ${configuration.maxPullPages} pull pages without reaching the stream tail."
            }
            val response = transport.pull(cursor, configuration.pullPageSize)
            validatePullPage(cursor, response.changes, response.nextCursor, response.hasMore)
            if (response.changes.isEmpty()) {
                return PullStats(pages, pulled, applied, ignored)
            }

            val merge = snapshotStore.merge(mapper.snapshot(response.changes)) {
                state.recordPulledPage(configuration.scopeId, response.changes, response.nextCursor)
            }
            pages++
            pulled += response.changes.size
            applied += merge.remoteApplied
            ignored += merge.remoteIgnored
            cursor = response.nextCursor
            if (!response.hasMore) return PullStats(pages, pulled, applied, ignored)
        }
    }

    private suspend fun pushPending(): PushStats {
        val totals = PushStats()
        while (true) {
            val pending = state.pending(configuration.scopeId, configuration.pushBatchSize)
            if (pending.isEmpty()) return totals
            val baseCursor = state.cursor(configuration.scopeId)
            val response = try {
                transport.push(
                    ServerSyncPushRequest(
                        deviceUid = configuration.deviceUid,
                        baseCursor = baseCursor,
                        mutations = pending.map(ServerSyncPendingMutation::mutation),
                    ),
                )
            } catch (failure: Throwable) {
                val message = failure.message?.take(MAX_PERSISTED_ERROR_LENGTH)
                pending.forEach { state.markAttempt(configuration.scopeId, it.mutation.mutationId, message) }
                throw failure
            }
            require(response.nextCursor == baseCursor) {
                "Push must not advance the pull cursor past unseen changes."
            }
            val results = validatePushResults(pending, response.results)
            val accepted = mutableListOf<Pair<String, ServerSyncChange>>()
            val conflicts = mutableListOf<Pair<String, ServerSyncChange>>()
            var rejected = 0
            var applied = 0
            var replayed = 0

            pending.forEach { item ->
                val result = requireNotNull(results[item.mutation.mutationId])
                when (result.status) {
                    ServerSyncMutationStatus.APPLIED -> {
                        accepted += item.mutation.mutationId to requireMatchingChange(item.mutation, result.change)
                        applied++
                    }
                    ServerSyncMutationStatus.REPLAYED -> {
                        accepted += item.mutation.mutationId to requireMatchingChange(item.mutation, result.change)
                        replayed++
                    }
                    ServerSyncMutationStatus.CONFLICT -> {
                        conflicts += item.mutation.mutationId to requireMatchingChange(item.mutation, result.latest)
                    }
                    ServerSyncMutationStatus.REJECTED -> {
                        state.markAttempt(
                            configuration.scopeId,
                            item.mutation.mutationId,
                            result.reason?.take(MAX_PERSISTED_ERROR_LENGTH) ?: "rejected",
                        )
                        rejected++
                    }
                }
            }
            state.recordAcceptedMutations(configuration.scopeId, accepted)
            if (conflicts.isNotEmpty()) {
                snapshotStore.merge(mapper.snapshot(conflicts.map { it.second })) {
                    conflicts.forEach { (mutationId, latest) ->
                        state.recordConflict(configuration.scopeId, mutationId, latest)
                    }
                }
            }
            totals.applied += applied
            totals.replayed += replayed
            totals.conflicts += conflicts.size
            totals.rejected += rejected
            if (conflicts.isNotEmpty() || rejected > 0) return totals
        }
    }

    private fun validatePullPage(
        cursor: Long,
        changes: List<ServerSyncChange>,
        nextCursor: Long,
        hasMore: Boolean,
    ) {
        require(nextCursor >= cursor) { "Server pull cursor moved backwards." }
        if (changes.isEmpty()) {
            require(nextCursor == cursor && !hasMore) {
                "An empty pull page cannot advance cursor or report more changes."
            }
            return
        }
        var previous = cursor
        changes.forEach { change ->
            require(change.serverVersion.changeId > previous) {
                "Server pull changes must be strictly ordered after cursor $cursor."
            }
            previous = change.serverVersion.changeId
        }
        require(nextCursor == previous) { "Pull next_cursor must equal the final change ID." }
    }

    private fun validatePushResults(
        pending: List<ServerSyncPendingMutation>,
        results: List<ServerSyncMutationResult>,
    ): Map<String, ServerSyncMutationResult> {
        val byId = results.associateBy(ServerSyncMutationResult::mutationId)
        require(byId.size == results.size) { "Push response contains duplicate mutation results." }
        require(byId.keys == pending.mapTo(mutableSetOf()) { it.mutation.mutationId }) {
            "Push response mutation IDs do not match the request."
        }
        return byId
    }

    private fun requireMatchingChange(
        mutation: ServerSyncMutation,
        change: ServerSyncChange?,
    ): ServerSyncChange = requireNotNull(change) {
        "Server result ${mutation.mutationId} is missing its document change."
    }.also {
        require(it.entityKind == mutation.entityKind && it.entityUid == mutation.entityUid) {
            "Server result ${mutation.mutationId} points to a different entity."
        }
    }

    private fun newMutationId(): String = buildString(MUTATION_ID_LENGTH) {
        append("core-")
        Random.nextBytes(MUTATION_RANDOM_BYTES).forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

    private companion object {
        const val MAX_PERSISTED_ERROR_LENGTH = 512
        const val MUTATION_RANDOM_BYTES = 16
        const val MUTATION_ID_LENGTH = 5 + MUTATION_RANDOM_BYTES * 2
        const val HEX_DIGITS = "0123456789abcdef"
    }
}

private data class PullStats(
    val pages: Int,
    val changes: Int,
    val applied: Int,
    val ignored: Int,
)

private data class PushStats(
    var applied: Int = 0,
    var replayed: Int = 0,
    var conflicts: Int = 0,
    var rejected: Int = 0,
)

private class MutableOutcome {
    var pullPages = 0
    var pulledChanges = 0
    var remoteApplied = 0
    var remoteIgnored = 0
    var pushed = 0
    var replayed = 0
    var conflicts = 0
    var rejected = 0

    fun add(stats: PullStats) {
        pullPages += stats.pages
        pulledChanges += stats.changes
        remoteApplied += stats.applied
        remoteIgnored += stats.ignored
    }

    fun add(stats: PushStats) {
        pushed += stats.applied
        replayed += stats.replayed
        conflicts += stats.conflicts
        rejected += stats.rejected
    }

    fun finish(remainingOutbox: Long) = DeadlinerServerSyncOutcome(
        pullPages = pullPages,
        pulledChanges = pulledChanges,
        remoteApplied = remoteApplied,
        remoteIgnored = remoteIgnored,
        pushed = pushed,
        replayed = replayed,
        conflicts = conflicts,
        rejected = rejected,
        remainingOutbox = remainingOutbox,
    )
}
