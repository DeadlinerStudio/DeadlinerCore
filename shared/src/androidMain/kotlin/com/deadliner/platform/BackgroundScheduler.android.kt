package com.deadliner.platform

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Android BackgroundScheduler actual 实现。
 *
 * 使用 WorkManager 实现后台任务调度。
 */
actual class BackgroundScheduler(private val context: Context) {
    private val workManager = WorkManager.getInstance(context)

    actual fun schedulePeriodicSync(config: PeriodicSyncConfig) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                when (config.requiresNetworkType) {
                    NetworkType.NOT_REQUIRED -> androidx.work.NetworkType.NOT_REQUIRED
                    NetworkType.CONNECTED -> androidx.work.NetworkType.CONNECTED
                    NetworkType.UNMETERED -> androidx.work.NetworkType.UNMETERED
                }
            )
            .setRequiresCharging(config.requiresCharging)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            config.intervalMinutes.toLong(),
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "deadliner_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    actual fun triggerSyncNow() {
        val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
            .build()

        workManager.enqueue(request)
    }

    actual fun cancelAll() {
        workManager.cancelAllWork()
    }

    actual fun isScheduled(): Boolean {
        // WorkManager 不提供检查任务是否已调度的 API
        return false
    }
}

/**
 * 同步 Worker。
 */
class SyncWorker(
    context: Context,
    params: androidx.work.WorkerParameters,
) : androidx.work.CoroutineWorker(context, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        // 执行同步逻辑
        return androidx.work.ListenableWorker.Result.success()
    }
}
