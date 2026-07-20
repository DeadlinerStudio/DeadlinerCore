package com.deadliner.platform

/** The macOS target is test-only and intentionally has no process background scheduler. */
actual class BackgroundScheduler {
    actual fun schedulePeriodicSync(config: PeriodicSyncConfig): Unit = unsupported()
    actual fun triggerSyncNow(): Unit = unsupported()
    actual fun cancelAll(): Unit = unsupported()
    actual fun isScheduled(): Boolean = false

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "BackgroundScheduler is unavailable in the macOS development target.",
    )
}
