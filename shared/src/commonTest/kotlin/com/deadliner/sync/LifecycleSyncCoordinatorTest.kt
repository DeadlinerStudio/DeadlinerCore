package com.deadliner.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifecycleSyncCoordinatorTest {
    @Test
    fun `foreground before configuration pulls without local mutation`() = runBlocking {
        val done = CompletableDeferred<Unit>()
        val coordinator = RepositorySyncCoordinator()
        try {
            coordinator.setForeground(true)
            coordinator.configure({ WebDavChangeLogSyncOutcome(succeeded = true) }, { done.complete(Unit) })
            withTimeout(2_000) { done.await() }
        } finally { coordinator.close() }
    }

    @Test
    fun `manual and background callers join running sync`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val coordinator = RepositorySyncCoordinator()
        try {
            coordinator.configure({
                runs++
                started.complete(Unit)
                release.await()
                WebDavChangeLogSyncOutcome(succeeded = true, remoteApplied = 7)
            })
            val first = async { coordinator.requestSync(SyncTrigger.MANUAL) }
            withTimeout(2_000) { started.await() }
            val second = async { coordinator.requestSync(SyncTrigger.BACKGROUND) }
            delay(30)
            release.complete(Unit)
            assertEquals(7, withTimeout(2_000) { first.await() }.remoteApplied)
            assertEquals(7, withTimeout(2_000) { second.await() }.remoteApplied)
            assertEquals(1, runs)
        } finally { coordinator.close() }
    }

    @Test
    fun `successful refresh is throttled but manual and mutation bypass window`() = runBlocking {
        var runs = 0
        val mutationDone = CompletableDeferred<Unit>()
        val coordinator = RepositorySyncCoordinator(debounceMillis = 1)
        try {
            coordinator.configure({
                runs++
                WebDavChangeLogSyncOutcome(succeeded = true)
            }, { if (runs == 3) mutationDone.complete(Unit) })
            assertTrue(coordinator.requestSync(SyncTrigger.FOREGROUND).succeeded)
            assertTrue(coordinator.requestSync(SyncTrigger.PERIODIC).succeeded)
            assertEquals(1, runs)
            assertTrue(coordinator.requestSync(SyncTrigger.MANUAL).succeeded)
            coordinator.notifyMutation()
            withTimeout(2_000) { mutationDone.await() }
            assertEquals(3, runs)
        } finally { coordinator.close() }
    }

    @Test
    fun `foreground failure retries without further mutation`() = runBlocking {
        var runs = 0
        val recovered = CompletableDeferred<Unit>()
        val coordinator = RepositorySyncCoordinator(retryInitialMillis = 5, retryMaxMillis = 20)
        try {
            coordinator.configure({
                runs++
                WebDavChangeLogSyncOutcome(succeeded = runs >= 3)
            }, { if (it.succeeded) recovered.complete(Unit) })
            coordinator.setForeground(true)
            withTimeout(2_000) { recovered.await() }
            assertEquals(3, runs)
        } finally { coordinator.close() }
    }

    @Test
    fun `background stops polling and foreground resumes remote checks`() = runBlocking {
        var runs = 0
        val second = CompletableDeferred<Unit>()
        val third = CompletableDeferred<Unit>()
        val coordinator = RepositorySyncCoordinator(pollIntervalMillis = 15, refreshWindowMillis = 0)
        try {
            coordinator.configure({ WebDavChangeLogSyncOutcome(succeeded = true) }, {
                runs++
                if (runs == 2) second.complete(Unit)
                if (runs == 3) third.complete(Unit)
            })
            coordinator.setForeground(true)
            withTimeout(2_000) { second.await() }
            coordinator.setForeground(false)
            delay(40)
            val paused = runs
            delay(50)
            assertEquals(paused, runs)
            coordinator.setForeground(true)
            withTimeout(2_000) { third.await() }
        } finally { coordinator.close() }
    }

    @Test
    fun `network restoration bypasses recent success window`() = runBlocking {
        var runs = 0
        val done = CompletableDeferred<Unit>()
        val coordinator = RepositorySyncCoordinator()
        try {
            coordinator.configure({ WebDavChangeLogSyncOutcome(succeeded = true) }, {
                runs++
                if (runs == 1) done.complete(Unit)
            })
            coordinator.setForeground(true)
            withTimeout(2_000) { done.await() }
            coordinator.setNetworkAvailable(false)
            assertFalse(coordinator.requestSync(SyncTrigger.MANUAL).succeeded)
            coordinator.setNetworkAvailable(true)
            // Join the restored-network round before the next assertion.
            assertTrue(coordinator.requestSync(SyncTrigger.FOREGROUND).succeeded)
            assertEquals(2, runs)
        } finally { coordinator.close() }
    }

    @Test
    fun `configuration change rejects obsolete result and waiter`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var oldCallbacks = 0
        var newCallbacks = 0
        val coordinator = RepositorySyncCoordinator()
        try {
            coordinator.configure({
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                }
                WebDavChangeLogSyncOutcome(succeeded = true, remoteApplied = 99)
            }, { oldCallbacks++ })
            val old = async { coordinator.requestSync(SyncTrigger.MANUAL) }
            withTimeout(2_000) { started.await() }
            coordinator.configure({ WebDavChangeLogSyncOutcome(succeeded = true, remoteApplied = 1) }, { newCallbacks++ })
            assertFalse(withTimeout(2_000) { old.await() }.succeeded)
            assertEquals(1, coordinator.requestSync(SyncTrigger.MANUAL).remoteApplied)
            release.complete(Unit)
            delay(30)
            assertEquals(0, oldCallbacks)
            assertEquals(1, newCallbacks)
        } finally { release.complete(Unit); coordinator.close() }
    }

    @Test
    fun `background expiration cancels sole request and later request works`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var runs = 0
        val coordinator = RepositorySyncCoordinator()
        try {
            coordinator.configure({
                runs++
                if (runs == 1) {
                    try { started.complete(Unit); CompletableDeferred<Unit>().await() }
                    finally { cancelled.complete(Unit) }
                }
                WebDavChangeLogSyncOutcome(succeeded = true)
            })
            val request = async { coordinator.requestSync(SyncTrigger.BACKGROUND) }
            withTimeout(2_000) { started.await() }
            request.cancelAndJoin()
            withTimeout(2_000) { cancelled.await() }
            assertTrue(coordinator.requestSync(SyncTrigger.BACKGROUND).succeeded)
            assertEquals(2, runs)
        } finally { coordinator.close() }
    }

    @Test
    fun `disable stops foreground retry and closed coordinator releases waiters`() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        var runs = 0
        val coordinator = RepositorySyncCoordinator(retryInitialMillis = 100)
        coordinator.configure({ runs++; WebDavChangeLogSyncOutcome(succeeded = false) }, { failed.complete(Unit) })
        coordinator.setForeground(true)
        withTimeout(2_000) { failed.await() }
        coordinator.disable()
        assertFalse(coordinator.requestSync(SyncTrigger.MANUAL).succeeded)
        delay(150)
        assertEquals(1, runs)
        coordinator.close()
        assertFalse(coordinator.requestSync(SyncTrigger.MANUAL).succeeded)
    }
    @Test
    fun `transport cancellation does not wedge the coordinator`() = runBlocking {
        var runs = 0
        val coordinator = RepositorySyncCoordinator()
        try {
            coordinator.configure({
                runs++
                if (runs == 1) throw kotlinx.coroutines.CancellationException("transport cancelled")
                WebDavChangeLogSyncOutcome(succeeded = true)
            })
            assertFalse(withTimeout(2_000) { coordinator.requestSync(SyncTrigger.MANUAL) }.succeeded)
            assertTrue(withTimeout(2_000) { coordinator.requestSync(SyncTrigger.MANUAL) }.succeeded)
            assertEquals(2, runs)
        } finally { coordinator.close() }
    }

    @Test
    fun `closing while request runs releases caller`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val coordinator = RepositorySyncCoordinator()
        coordinator.configure({
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
            WebDavChangeLogSyncOutcome(succeeded = true)
        })
        val request = async { coordinator.requestSync(SyncTrigger.BACKGROUND) }
        withTimeout(2_000) { started.await() }
        coordinator.close()
        assertFalse(withTimeout(2_000) { request.await() }.succeeded)
    }

    @Test
    fun `leaving foreground stops failure retry`() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        var runs = 0
        val coordinator = RepositorySyncCoordinator(retryInitialMillis = 80)
        try {
            coordinator.configure({ runs++; WebDavChangeLogSyncOutcome(succeeded = false) }, { failed.complete(Unit) })
            coordinator.setForeground(true)
            withTimeout(2_000) { failed.await() }
            coordinator.setForeground(false)
            delay(120)
            assertEquals(1, runs)
        } finally { coordinator.close() }
    }

    @Test
    fun `cancelling background waiter preserves joined manual request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = RepositorySyncCoordinator()
        try {
            coordinator.configure({
                started.complete(Unit)
                release.await()
                WebDavChangeLogSyncOutcome(succeeded = true)
            })
            val background = async { coordinator.requestSync(SyncTrigger.BACKGROUND) }
            withTimeout(2_000) { started.await() }
            val manual = async { coordinator.requestSync(SyncTrigger.MANUAL) }
            delay(20)
            coordinator.cancelBackgroundSync()
            delay(20)
            assertTrue(background.isCancelled)
            release.complete(Unit)
            assertTrue(withTimeout(2_000) { manual.await() }.succeeded)
        } finally { release.complete(Unit); coordinator.close() }
    }

    @Test
    fun `native callback handle cancels coroutine without completion callback`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var callbacks = 0
        val coordinator = RepositorySyncCoordinator()
        try {
            coordinator.configure({
                try { started.complete(Unit); CompletableDeferred<Unit>().await() }
                finally { cancelled.complete(Unit) }
                WebDavChangeLogSyncOutcome(succeeded = true)
            })
            val request = coordinator.startSync(SyncTrigger.BACKGROUND) { callbacks++ }
            withTimeout(2_000) { started.await() }
            request.cancel()
            withTimeout(2_000) { cancelled.await() }
            assertEquals(0, callbacks)
        } finally { coordinator.close() }
    }

}
