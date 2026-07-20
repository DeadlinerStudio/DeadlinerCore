package com.deadliner.platform

/**
 * HarmonyOS PlatformLogger actual 实现。
 *
 * 使用 hilog 实现日志输出。
 */
actual class PlatformLogger {
    actual fun d(tag: String, message: String) {
        println("[DEBUG] $tag: $message")
    }

    actual fun i(tag: String, message: String) {
        println("[INFO] $tag: $message")
    }

    actual fun w(tag: String, message: String) {
        println("[WARN] $tag: $message")
    }

    actual fun e(tag: String, message: String, error: Throwable?) {
        println("[ERROR] $tag: $message ${error?.message ?: ""}")
    }
}
