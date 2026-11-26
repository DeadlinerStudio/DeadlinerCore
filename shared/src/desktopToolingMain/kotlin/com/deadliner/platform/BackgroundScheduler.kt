package com.deadliner.platform

actual class BackgroundScheduler {
    actual fun schedulePeriodicSync(config: PeriodicSyncConfig): Unit = unsupported()
    actual fun triggerSyncNow(): Unit = unsupported()
    actual fun cancelAll(): Unit = unsupported()
    actual fun isScheduled(): Boolean = false

    private fun unsupported(): Nothing = throw UnsupportedOperationException(
        "BackgroundScheduler is unavailable for desktop tooling.",
    )
}
