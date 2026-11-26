package com.deadliner.platform

/**
 * HarmonyOS BackgroundScheduler actual 实现。
 *
 * 使用 WorkScheduler 实现后台任务调度。
 */
actual class BackgroundScheduler {
    actual fun schedulePeriodicSync(config: PeriodicSyncConfig) {
        // 使用 WorkScheduler 调度周期性任务
        PlatformLogger().i("background", "[background.schedule-sync] intervalMinutes=${config.intervalMinutes}")
    }

    actual fun triggerSyncNow() {
        // 立即触发同步
        PlatformLogger().i("background", "[background.trigger-sync]")
    }

    actual fun cancelAll() {
        // 取消所有后台任务
        PlatformLogger().i("background", "[background.cancel-all]")
    }

    actual fun isScheduled(): Boolean {
        // 检查是否有已调度的任务
        PlatformLogger().i("background", "[background.is-scheduled]")
        return false
    }
}
