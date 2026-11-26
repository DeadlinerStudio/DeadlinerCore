package com.deadliner.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/** Platform events are inputs; all pull scheduling policy belongs to this database-scoped actor. */
/** Explicit cancellation handle for native callback bridges (Swift Task cancellation is not forwarded). */
class SyncRequest internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

enum class SyncTrigger { MANUAL, FOREGROUND, PERIODIC, NETWORK, BACKGROUND }

class RepositorySyncCoordinator(
    private val debounceMillis: Long = 400L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val pollIntervalMillis: Long = 60_000L,
    private val refreshWindowMillis: Long = 10_000L,
    private val retryInitialMillis: Long = 5_000L,
    private val retryMaxMillis: Long = 300_000L,
    private val nowMillis: () -> Long = monotonicClock(),
) : RepositoryMutationNotifier, AutoCloseable {
    private val signals = Channel<Signal>(Channel.UNLIMITED)

    init {
        require(pollIntervalMillis > 0 && retryInitialMillis > 0 && retryMaxMillis >= retryInitialMillis)
        scope.launch { processSignals() }
    }

    override fun notifyMutation() { signals.trySend(Signal.Mutation) }

    fun configure(
        operation: suspend () -> WebDavChangeLogSyncOutcome,
        onComplete: ((WebDavChangeLogSyncOutcome) -> Unit)? = null,
    ) { signals.trySend(Signal.Configure(operation, onComplete)) }

    fun setForeground(active: Boolean) { signals.trySend(Signal.Foreground(active)) }
    fun setNetworkAvailable(available: Boolean) { signals.trySend(Signal.Network(available)) }
    fun disable() { signals.trySend(Signal.Disable) }

    /** Waits for a real sync (or the already running sync). Cancellation releases the waiter. */
    @Throws(CancellationException::class)
    suspend fun requestSync(trigger: SyncTrigger): WebDavChangeLogSyncOutcome {
        val reply = CompletableDeferred<WebDavChangeLogSyncOutcome>()
        if (!signals.trySend(Signal.Request(trigger, reply)).isSuccess) return failure("Sync coordinator is closed")
        return try {
            reply.await()
        } finally {
            signals.trySend(Signal.CancelRequest(reply))
        }
    }

    fun startSync(trigger: SyncTrigger, onComplete: (WebDavChangeLogSyncOutcome) -> Unit): SyncRequest =
        SyncRequest(scope.launch { onComplete(requestSync(trigger)) })

    fun cancelBackgroundSync() { signals.trySend(Signal.CancelBackground) }

    override fun close() {
        signals.close()
        scope.cancel()
    }

    private suspend fun processSignals() {
        var operation: (suspend () -> WebDavChangeLogSyncOutcome)? = null
        var onComplete: ((WebDavChangeLogSyncOutcome) -> Unit)? = null
        var foreground = false
        var online = true
        var dirty = false
        var epoch = 0L
        var runId = 0L
        var activeRun = 0L
        var runJob: Job? = null
        var timer: Job? = null
        var timerId = 0L
        var retryScheduled = false
        var polling: Job? = null
        var retryMillis = retryInitialMillis
        var lastSuccess: Long? = null
        var automaticRun = false
        val waiters = mutableMapOf<CompletableDeferred<WebDavChangeLogSyncOutcome>, SyncTrigger>()

        fun stopTimer() { timer?.cancel(); timer = null; timerId++; retryScheduled = false }
        fun stopPolling() { polling?.cancel(); polling = null }
        fun completeWaiters(outcome: WebDavChangeLogSyncOutcome) {
            waiters.keys.forEach { it.complete(outcome) }
            waiters.clear()
        }
        fun startPolling() {
            stopPolling()
            if (!foreground || !online || operation == null) return
            val currentEpoch = epoch
            polling = scope.launch {
                while (true) {
                    delay(pollIntervalMillis)
                    signals.send(Signal.Poll(currentEpoch))
                }
            }
        }
        fun schedule(wait: Long, retry: Boolean = false) {
            stopTimer()
            retryScheduled = retry
            val currentEpoch = epoch
            val currentTimer = timerId
            timer = scope.launch {
                delay(wait)
                signals.send(Signal.Run(currentEpoch, currentTimer))
            }
        }
        fun launchSync(automatic: Boolean) {
            val action = operation ?: return
            stopTimer()
            val currentEpoch = epoch
            val currentRun = ++runId
            activeRun = currentRun
            automaticRun = automatic
            dirty = false
            runJob = scope.launch {
                val outcome = try {
                    action()
                } catch (cancelled: CancellationException) {
                    signals.trySend(Signal.Finished(currentEpoch, currentRun, failure("Sync cancelled")))
                    throw cancelled
                } catch (error: Throwable) {
                    failure(error.message ?: "Automatic sync failed")
                }
                signals.send(Signal.Finished(currentEpoch, currentRun, outcome))
            }
        }
        fun request(trigger: SyncTrigger, reply: CompletableDeferred<WebDavChangeLogSyncOutcome>? = null) {
            if (reply?.isCancelled == true) return
            if (operation == null) { reply?.complete(failure("Automatic sync is not configured")); return }
            if (!online) { reply?.complete(failure("Network is unavailable")); return }
            if (activeRun != 0L) {
                if (reply != null) waiters[reply] = trigger else automaticRun = true
                return
            }
            val force = trigger == SyncTrigger.MANUAL || trigger == SyncTrigger.BACKGROUND || trigger == SyncTrigger.NETWORK
            val recent = lastSuccess?.let { nowMillis() - it < refreshWindowMillis } ?: false
            if (!force && !dirty && recent) {
                reply?.complete(WebDavChangeLogSyncOutcome(succeeded = true))
                return
            }
            if (reply != null) waiters[reply] = trigger
            launchSync(automatic = reply == null)
        }
        fun reset() {
            epoch++
            runJob?.cancel()
            runJob = null
            activeRun = 0L
            stopTimer()
            stopPolling()
            completeWaiters(failure("Sync configuration changed"))
            lastSuccess = null
            retryMillis = retryInitialMillis
        }

        try {
            for (signal in signals) {
                when (signal) {
                    is Signal.Configure -> {
                        reset()
                        operation = signal.operation
                        onComplete = signal.onComplete
                        startPolling()
                        if (foreground) request(SyncTrigger.FOREGROUND)
                        else if (dirty && online) schedule(debounceMillis)
                    }
                    Signal.Disable -> {
                        reset()
                        operation = null
                        onComplete = null
                    }
                    is Signal.Foreground -> {
                        val changed = foreground != signal.active
                        foreground = signal.active
                        if (changed) {
                            startPolling()
                            if (foreground) request(SyncTrigger.FOREGROUND) else stopTimer()
                        }
                    }
                    is Signal.Network -> {
                        val restored = !online && signal.available
                        online = signal.available
                        startPolling()
                        if (!online) stopTimer()
                        else if (restored && foreground) request(SyncTrigger.NETWORK)
                    }
                    Signal.Mutation -> {
                        dirty = true
                        // In-flight writes are never absorbed by the current round.
                        if (activeRun == 0L && online && operation != null) schedule(debounceMillis)
                    }
                    is Signal.Request -> request(signal.trigger, signal.reply)
                    Signal.CancelBackground -> {
                        waiters.filterValues { it == SyncTrigger.BACKGROUND }.keys.toList().forEach { it.cancel() }
                    }
                    is Signal.CancelRequest -> {
                        waiters.remove(signal.reply)
                        if (activeRun != 0L && waiters.isEmpty() && !automaticRun) {
                            runJob?.cancel()
                            activeRun = 0L
                            dirty = true
                            if (foreground && online) schedule(retryInitialMillis)
                        }
                    }
                    is Signal.Poll -> if (signal.epoch == epoch && foreground && online && !retryScheduled) request(SyncTrigger.PERIODIC)
                    is Signal.Run -> if (signal.epoch == epoch && signal.timerId == timerId && online) {
                        if (activeRun == 0L) launchSync(automatic = true)
                    }
                    is Signal.Finished -> {
                        if (signal.epoch != epoch || signal.runId != activeRun) continue
                        activeRun = 0L
                        runJob = null
                        if (signal.outcome.succeeded) {
                            lastSuccess = nowMillis()
                            retryMillis = retryInitialMillis
                        } else dirty = true
                        // A faulty native consumer must not destroy the scheduling actor.
                        runCatching { onComplete?.invoke(signal.outcome) }
                        completeWaiters(signal.outcome)
                        if (signal.outcome.succeeded && dirty && online) launchSync(automatic = true)
                        else if (!signal.outcome.succeeded && foreground && online) {
                            schedule(retryMillis, retry = true)
                            retryMillis = (retryMillis * 2).coerceAtMost(retryMaxMillis)
                        }
                    }
                }
            }
        } finally {
            runJob?.cancel()
            stopTimer()
            stopPolling()
            completeWaiters(failure("Sync coordinator is closed"))
            while (true) {
                val queued = signals.tryReceive().getOrNull() ?: break
                if (queued is Signal.Request) queued.reply.complete(failure("Sync coordinator is closed"))
            }
        }
    }

    private sealed interface Signal {
        data class Configure(val operation: suspend () -> WebDavChangeLogSyncOutcome,
            val onComplete: ((WebDavChangeLogSyncOutcome) -> Unit)?) : Signal
        data object Disable : Signal
        data object CancelBackground : Signal
        data object Mutation : Signal
        data class Foreground(val active: Boolean) : Signal
        data class Network(val available: Boolean) : Signal
        data class Request(val trigger: SyncTrigger, val reply: CompletableDeferred<WebDavChangeLogSyncOutcome>) : Signal
        data class CancelRequest(val reply: CompletableDeferred<WebDavChangeLogSyncOutcome>) : Signal
        data class Poll(val epoch: Long) : Signal
        data class Run(val epoch: Long, val timerId: Long) : Signal
        data class Finished(val epoch: Long, val runId: Long, val outcome: WebDavChangeLogSyncOutcome) : Signal
    }

    companion object {
        private fun failure(message: String) = WebDavChangeLogSyncOutcome(succeeded = false, failureMessage = message)
        private fun monotonicClock(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
