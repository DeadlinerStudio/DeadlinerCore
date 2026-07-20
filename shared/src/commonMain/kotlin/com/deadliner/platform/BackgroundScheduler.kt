package com.deadliner.platform

/**
 * 平台后台任务调度抽象。
 *
 * 各平台 actual 实现：
 * - iOS: BGTaskScheduler
 * - Android: WorkManager
 * - HarmonyOS: WorkScheduler
 */
expect class BackgroundScheduler {
    /** 调度周期性同步任务 */
    fun schedulePeriodicSync(config: PeriodicSyncConfig)

    /** 立即触发一次同步 */
    fun triggerSyncNow()

    /** 取消所有后台任务 */
    fun cancelAll()

    /** 检查后台任务是否已调度 */
    fun isScheduled(): Boolean
}

/** 周期性同步配置（易于扩展） */
data class PeriodicSyncConfig(
    val intervalMinutes: Int = 15,  // 最小间隔
    val requiresCharging: Boolean = false,
    val requiresNetworkType: NetworkType = NetworkType.CONNECTED,
)

/** 网络类型（密封类，编译时类型安全） */
enum class NetworkType {
    NOT_REQUIRED, CONNECTED, UNMETERED
}
