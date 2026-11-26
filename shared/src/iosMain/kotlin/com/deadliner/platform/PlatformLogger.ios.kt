package com.deadliner.platform

/**
 * iOS PlatformLogger actual 实现。
 *
 * 输出到标准输出，以便宿主应用将 KMP 与 Swift 日志收集到同一个滚动缓冲区。
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
        println("[ERROR] $tag: $message ${error?.message.orEmpty()}")
    }
}

/** iOS captures KMP stdout through AIStdStreamCapture into its AppLog buffer. */
actual object PlatformLogSink {
    actual fun configure(directory: String, processId: Long?) = Unit

    actual fun write(level: LogLevel, category: String, message: String, errorMessage: String?) {
        val errorSuffix = errorMessage?.takeIf { it.isNotBlank() }?.let { ": " + it }.orEmpty()
        println("[" + level.name + "] " + category + ": " + message + errorSuffix)
    }
}
