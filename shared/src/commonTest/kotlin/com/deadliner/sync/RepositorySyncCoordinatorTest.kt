package com.deadliner.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class RepositorySyncCoordinatorTest {
    @Test
    fun `coalesces rapid mutations into one sync`() = runBlocking {
        val completed = CompletableDeferred<Unit>()
        var runs = 0
        val coordinator = RepositorySyncCoordinator(debounceMillis = 20)
        try {
            coordinator.configure(
                operation = {
                    runs++
                    WebDavChangeLogSyncOutcome(succeeded = true)
                },
                onComplete = { completed.complete(Unit) },
            )
            repeat(5) { coordinator.notifyMutation() }

            withTimeout(1_000) { completed.await() }
            delay(60)
            assertEquals(1, runs)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `runs once more when a mutation arrives during sync`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondCompleted = CompletableDeferred<Unit>()
        var runs = 0
        val coordinator = RepositorySyncCoordinator(debounceMillis = 10)
        try {
            coordinator.configure(
                operation = {
                    runs++
                    if (runs == 1) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    WebDavChangeLogSyncOutcome(succeeded = true)
                },
                onComplete = { if (runs == 2) secondCompleted.complete(Unit) },
            )
            coordinator.notifyMutation()
            withTimeout(1_000) { firstStarted.await() }
            coordinator.notifyMutation()
            delay(30)
            releaseFirst.complete(Unit)

            withTimeout(1_000) { secondCompleted.await() }
            assertEquals(2, runs)
        } finally {
            coordinator.close()
        }
    }
}
