package com.deadliner.platform

/**
 * 平台日志抽象。
 *
 * 各平台 actual 实现：
 * - iOS: os.Logger
 * - Android: android.util.Log
 * - HarmonyOS: hilog
 */
expect class PlatformLogger {
    /** Debug 级别日志 */
    fun d(tag: String, message: String)

    /** Info 级别日志 */
    fun i(tag: String, message: String)

    /** Warning 级别日志 */
    fun w(tag: String, message: String)

    /** Error 级别日志 */
    fun e(tag: String, message: String, error: Throwable? = null)
}

/** 日志级别（密封类，编译时类型安全） */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}
