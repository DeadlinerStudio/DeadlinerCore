package com.deadliner.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getpid
import platform.posix.mkdir
import kotlin.experimental.ExperimentalNativeApi
import kotlin.random.Random

/**
 * HarmonyOS PlatformLogger actual 实现。
 *
 * KMP Native 直接追加到 HarmonyOS 宿主配置的 Diagnostics 目录。
 */
actual class PlatformLogger {
    actual fun d(tag: String, message: String) {
        PlatformLogSink.write(LogLevel.DEBUG, tag, message)
    }

    actual fun i(tag: String, message: String) {
        PlatformLogSink.write(LogLevel.INFO, tag, message)
    }

    actual fun w(tag: String, message: String) {
        PlatformLogSink.write(LogLevel.WARN, tag, message)
    }

    actual fun e(tag: String, message: String, error: Throwable?) {
        PlatformLogSink.write(LogLevel.ERROR, tag, message, error?.message)
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
actual object PlatformLogSink {
    private val sessionId = Random.nextBytes(16).joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
    private var diagnosticsDirectory: String? = null
    private var processId: Long? = null

    actual fun configure(directory: String, processId: Long?) {
        diagnosticsDirectory = directory.trimEnd('/') + "/Diagnostics"
        this.processId = processId ?: getpid().toLong()
        mkdir(requireNotNull(diagnosticsDirectory), 0x1EDu)
    }

    actual fun write(level: LogLevel, category: String, message: String, errorMessage: String?) {
        val directory = diagnosticsDirectory ?: return
        val record = PlatformLogFormat.record(level, category, message, sessionId, processId, errorMessage)
        append(directory + "/deadliner-diagnostics.jsonl", PlatformLogFormat.jsonLine(record))
        append(directory + "/deadliner-diagnostics.log", PlatformLogFormat.standardLine(record))
    }

    private fun append(path: String, line: String) {
        val handle = fopen(path, "a")
        if (handle != null) {
            fputs(line, handle)
            fclose(handle)
        }
    }
}
