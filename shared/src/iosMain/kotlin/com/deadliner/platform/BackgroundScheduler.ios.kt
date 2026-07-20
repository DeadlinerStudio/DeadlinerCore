package com.deadliner.platform

import platform.BackgroundTasks.BGTaskScheduler
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGProcessingTaskRequest

/**
 * iOS BackgroundScheduler actual 实现。
 *
 * 使用 BGTaskScheduler 实现后台任务调度。
 */
actual class BackgroundScheduler {
    private val scheduler = BGTaskScheduler.sharedScheduler

    actual fun schedulePeriodicSync(config: PeriodicSyncConfig) {
        val request = BGAppRefreshTaskRequest("com.deadliner.sync")
        request.earliestBeginDate = platform.Foundation.NSDate.dateWithTimeIntervalSinceNow(
            config.intervalMinutes.toLong() * 60,
        )

        scheduler.submitTaskRequest(request, null)
    }

    actual fun triggerSyncNow() {
        // iOS 不支持立即触发后台任务，需要等待系统调度
        // 可以通过通知用户手动触发
    }

    actual fun cancelAll() {
        scheduler.cancelAllTaskRequests()
    }

    actual fun isScheduled(): Boolean {
        // iOS 不提供检查任务是否已调度的 API
        return false
    }
}
