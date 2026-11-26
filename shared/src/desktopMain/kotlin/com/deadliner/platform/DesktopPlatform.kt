package com.deadliner.platform

import java.util.UUID

actual object PlatformIdentity {
    private lateinit var identity: String
    fun configure(deviceId: String) { identity = deviceId }
    actual fun deviceId(): String = identity
    actual fun newId(): String = UUID.randomUUID().toString()
}

actual class PlatformLogger {
    actual fun d(tag: String, message: String) = PlatformLogSink.write(LogLevel.DEBUG, tag, message)
    actual fun i(tag: String, message: String) = PlatformLogSink.write(LogLevel.INFO, tag, message)
    actual fun w(tag: String, message: String) = PlatformLogSink.write(LogLevel.WARN, tag, message)
    actual fun e(tag: String, message: String, error: Throwable?) =
        PlatformLogSink.write(LogLevel.ERROR, tag, message, error?.message)
}

actual object PlatformLogSink {
    actual fun configure(directory: String, processId: Long?) = Unit
    actual fun write(level: LogLevel, category: String, message: String, errorMessage: String?) {
        System.err.println("[$level][$category] $message" + errorMessage?.let { " ($it)" }.orEmpty())
    }
}
