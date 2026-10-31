@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.deadliner.bridge

import com.deadliner.sync.SyncTrigger
import com.deadliner.sync.WebDavChangeLogSyncOutcome
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.native.CName

private val lifecycleSyncJson = Json { encodeDefaults = true }

/** Fixed-buffer C ABI; capacity queries never start network work. */
@CName("deadliner_persistence_request_sync_json")
fun deadlinerPersistenceRequestSyncJson(trigger: Int, output: CPointer<ByteVar>?, capacity: Int): Int {
    // Native async bridge allocates one bounded buffer; no global response cache across callers.
    if (output == null || capacity < 4096) return 4096
    val outcome = try {
        val database = PersistenceRuntime.database ?: return 0
        val selected = SyncTrigger.entries.getOrNull(trigger) ?: return 0
        runBlocking { withTimeout(90_000) { database.requestSync(selected) } }
    } catch (error: Throwable) {
        WebDavChangeLogSyncOutcome(succeeded = false, failureMessage = "Sync interrupted or failed")
    }
    return writeCallerOwnedJson(lifecycleSyncJson.encodeToString(outcome), output, capacity)
}

@CName("deadliner_persistence_sync_event")
fun deadlinerPersistenceSyncEvent(event: Int, value: Int): Int {
    val database = PersistenceRuntime.database ?: return 0
    when (event) {
        0 -> database.setSyncForeground(value != 0)
        1 -> database.setSyncNetworkAvailable(value != 0)
        2 -> database.cancelBackgroundSync()
        else -> return 0
    }
    return 1
}
