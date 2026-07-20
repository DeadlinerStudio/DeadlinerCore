package com.deadliner.platform

actual class PlatformLogger {
    actual fun d(tag: String, message: String) = log("DEBUG", tag, message, null)
    actual fun i(tag: String, message: String) = log("INFO", tag, message, null)
    actual fun w(tag: String, message: String) = log("WARN", tag, message, null)
    actual fun e(tag: String, message: String, error: Throwable?) = log("ERROR", tag, message, error)

    private fun log(level: String, tag: String, message: String, error: Throwable?) {
        println("[$level] $tag: $message${error?.let { ": ${it.message}" } ?: ""}")
    }
}
