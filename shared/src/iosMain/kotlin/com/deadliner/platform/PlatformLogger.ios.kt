package com.deadliner.platform

import platform.Foundation.NSLog

/**
 * iOS PlatformLogger actual 实现。
 *
 * 使用 NSLog 实现日志输出。
 */
actual class PlatformLogger {
    actual fun d(tag: String, message: String) {
        NSLog("[DEBUG] %@: %@", tag, message)
    }

    actual fun i(tag: String, message: String) {
        NSLog("[INFO] %@: %@", tag, message)
    }

    actual fun w(tag: String, message: String) {
        NSLog("[WARN] %@: %@", tag, message)
    }

    actual fun e(tag: String, message: String, error: Throwable?) {
        NSLog("[ERROR] %@: %@ %@", tag, message, error?.message ?: "")
    }
}
