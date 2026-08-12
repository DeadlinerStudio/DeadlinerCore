package com.deadliner.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Database-scoped write-behind sync scheduler.
 *
 * Repository writes only enqueue a signal. Network work is debounced, runs outside SQL
 * transactions, and is single-flight. A mutation that lands during a sync guarantees one
 * follow-up run after the current operation finishes.
 */
class RepositorySyncCoordinator(
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : RepositoryMutationNotifier, AutoCloseable {
    private val signals = Channel<Signal>(Channel.UNLIMITED)

    init {
        scope.launch { processSignals() }
    }

    override fun notifyMutation() {
        signals.trySend(Signal.Mutation)
    }

    fun configure(
        operation: suspend () -> WebDavChangeLogSyncOutcome,
        onComplete: ((WebDavChangeLogSyncOutcome) -> Unit)? = null,
    ) {
        signals.trySend(Signal.Configure(operation, onComplete))
    }

    fun disable() {
        signals.trySend(Signal.Disable)
    }

    override fun close() {
        signals.close()
        scope.cancel()
    }

    private suspend fun processSignals() {
        var operation: (suspend () -> WebDavChangeLogSyncOutcome)? = null
        var onComplete: ((WebDavChangeLogSyncOutcome) -> Unit)? = null
        var debounceJob: Job? = null
        var running = false
        var pending = false
        var dirty = false
        var generation = 0L

        suspend fun launchSync() {
            val currentOperation = operation ?: return
            running = true
            scope.launch {
                val outcome = try {
                    currentOperation()
                } catch (error: Throwable) {
                    WebDavChangeLogSyncOutcome(
                        succeeded = false,
                        failureMessage = error.message ?: error::class.simpleName ?: "Unknown automatic sync failure",
                    )
                }
                signals.trySend(Signal.Finished(outcome))
            }
        }

        for (signal in signals) {
            when (signal) {
                is Signal.Configure -> {
                    operation = signal.operation
                    onComplete = signal.onComplete
                    if (dirty) {
                        generation++
                        val scheduledGeneration = generation
                        debounceJob?.cancel()
                        debounceJob = scope.launch {
                            delay(debounceMillis)
                            signals.trySend(Signal.Run(scheduledGeneration))
                        }
                    }
                }
                Signal.Disable -> {
                    operation = null
                    onComplete = null
                    pending = false
                    debounceJob?.cancel()
                    debounceJob = null
                }
                Signal.Mutation -> {
                    dirty = true
                    if (operation == null) continue
                    generation++
                    val scheduledGeneration = generation
                    debounceJob?.cancel()
                    debounceJob = scope.launch {
                        delay(debounceMillis)
                        signals.trySend(Signal.Run(scheduledGeneration))
                    }
                }
                is Signal.Run -> {
                    if (signal.generation != generation || operation == null) continue
                    debounceJob = null
                    if (running) pending = true else {
                        dirty = false
                        launchSync()
                    }
                }
                is Signal.Finished -> {
                    running = false
                    onComplete?.invoke(signal.outcome)
                    if (pending && operation != null) {
                        pending = false
                        dirty = false
                        launchSync()
                    }
                }
            }
        }
    }

    private sealed interface Signal {
        data class Configure(
            val operation: suspend () -> WebDavChangeLogSyncOutcome,
            val onComplete: ((WebDavChangeLogSyncOutcome) -> Unit)?,
        ) : Signal
        data object Disable : Signal
        data object Mutation : Signal
        data class Run(val generation: Long) : Signal
        data class Finished(val outcome: WebDavChangeLogSyncOutcome) : Signal
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 400L
    }
}
