package com.deadliner.platform

import android.util.Log

/**
 * Android PlatformLogger actual 实现。
 *
 * 使用 android.util.Log 实现日志输出。
 */
actual class PlatformLogger {
    actual fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    actual fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    actual fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    actual fun e(tag: String, message: String, error: Throwable?) {
        Log.e(tag, message, error)
    }
}
