package com.deadliner.sync

import com.deadliner.db.DeadlinerDatabase
import com.deadliner.db.MacosDatabaseDriverFactory
import com.deadliner.domain.task.Task
import com.deadliner.sync.transport.SyncBlob
import com.deadliner.sync.transport.SyncBlobTransport
import com.deadliner.sync.transport.SyncTransportPreconditionFailedException
import com.deadliner.sync.transport.SyncWriteCondition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs real repositories and V3 merge/upload against provider-neutral conditional blob storage. */
class LifecycleSyncIntegrationTest {
    @Test
    fun `opening receiving database pulls tasks without a local write`() = runBlocking {
        withDevices { source, target, transport ->
            val remoteTask = task("remote", "Changed on another device")
            source.tasks.create(remoteTask)
            assertTrue(source.webDavChangeLogSync.synchronizeSafely(transport).succeeded)
            val applied = CompletableDeferred<WebDavChangeLogSyncOutcome>()
            // Native lifecycle can arrive before credentials finish loading.
            target.setSyncForeground(true)
            target.configureAutomaticTransportSync(transport) { applied.complete(it) }
            val result = withTimeout(10_000) { applied.await() }
            assertTrue(result.succeeded)
            assertTrue(SyncResourceKind.TASK in result.appliedResources)
            assertEquals(remoteTask, target.tasks.find(remoteTask.uid))
        }
    }

    @Test
    fun `foreground sync merges remote task and uploads existing local pending task`() = runBlocking {
        withDevices { source, target, transport ->
            val remoteTask = task("remote", "Remote task")
            val localTask = task("local", "Offline task")
            source.tasks.create(remoteTask)
            assertTrue(source.webDavChangeLogSync.synchronizeSafely(transport).succeeded)
            target.tasks.create(localTask)
            target.configureAutomaticTransportSync(transport)
            target.setSyncForeground(true)
            val result = withTimeout(10_000) { target.requestSync(SyncTrigger.FOREGROUND) }
            assertTrue(result.succeeded)
            assertEquals(remoteTask, target.tasks.find(remoteTask.uid))
            assertEquals(localTask, target.tasks.find(localTask.uid))
            assertTrue(source.webDavChangeLogSync.synchronizeSafely(transport).succeeded)
            assertEquals(localTask, source.tasks.find(localTask.uid))
            assertNotNull(source.tasks.find(remoteTask.uid))
        }
    }

    private fun task(uid: String, title: String) = Task(
        uid = uid, title = title,
        createdAt = "2026-10-29T03:00:00Z", updatedAt = "2026-10-29T03:00:00Z",
    )

    private suspend fun withDevices(block: suspend (DeadlinerDatabase, DeadlinerDatabase, SyncBlobTransport) -> Unit) {
        val sourcePath = NSTemporaryDirectory() + "sync-source-" + NSUUID().UUIDString + ".db"
        val targetPath = NSTemporaryDirectory() + "sync-target-" + NSUUID().UUIDString + ".db"
        val source = DeadlinerDatabase.create(MacosDatabaseDriverFactory(sourcePath, "source"))
        val target = DeadlinerDatabase.create(MacosDatabaseDriverFactory(targetPath, "target"))
        try { block(source, target, ConditionalMemoryTransport()) }
        finally {
            source.close()
            target.close()
            remove(sourcePath)
            remove(targetPath)
        }
    }

    private class ConditionalMemoryTransport : SyncBlobTransport {
        private val blobs = mutableMapOf<String, SyncBlob>()
        private var revision = 0
        override suspend fun read(path: String): SyncBlob? = blobs[path]
        override suspend fun write(path: String, bytes: ByteArray, condition: SyncWriteCondition) {
            val existing = blobs[path]
            val matches = when (condition) {
                SyncWriteCondition.CreateOnly -> existing == null
                is SyncWriteCondition.MatchVersion -> existing?.version == condition.value
                SyncWriteCondition.Unconditional -> true
            }
            if (!matches) throw SyncTransportPreconditionFailedException()
            revision++
            blobs[path] = SyncBlob(bytes.copyOf(), "v" + revision)
        }
        override suspend fun ensureCollection(path: String): Boolean = true
    }
}
