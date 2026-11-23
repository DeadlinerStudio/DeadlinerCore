package com.deadliner.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLog
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getpid
import platform.posix.mkdir
import kotlin.random.Random

/**
 * macOS PlatformLogger actual 实现。
 *
 * A GUI app's stdout is discarded, so records go to the Diagnostics directory the
 * host configures (same JSONL / text files as Android and HarmonyOS). Warnings and
 * errors are also mirrored to the unified log. Before `configure`, nothing is lost
 * silently: records fall back to the unified log.
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

@OptIn(ExperimentalForeignApi::class)
actual object PlatformLogSink {
    private val sessionId = Random.nextBytes(16).joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
    private var diagnosticsDirectory: String? = null
    private var processId: Long? = null

    actual fun configure(directory: String, processId: Long?) {
        val diagnostics = directory.trimEnd('/') + "/Diagnostics"
        mkdir(diagnostics, 0x1EDu)
        this.processId = processId ?: getpid().toLong()
        diagnosticsDirectory = diagnostics
    }

    actual fun write(level: LogLevel, category: String, message: String, errorMessage: String?) {
        val directory = diagnosticsDirectory
        if (directory == null || level == LogLevel.WARN || level == LogLevel.ERROR) {
            val errorSuffix = errorMessage?.takeIf { it.isNotBlank() }?.let { ": " + it }.orEmpty()
            // Kotlin/Native cannot pass object varargs to NSLog (it traps), so the text is the
            // format itself with every `%` escaped.
            NSLog(("[" + level.name + "] " + category + ": " + message + errorSuffix).replace("%", "%%"))
        }
        if (directory == null) return
        val record = PlatformLogFormat.record(level, category, message, sessionId, processId, errorMessage)
        append(directory + "/deadliner-diagnostics.jsonl", PlatformLogFormat.jsonLine(record))
        append(directory + "/deadliner-diagnostics.log", PlatformLogFormat.standardLine(record))
    }

    /** One `fputs` per record on an append-mode stream keeps concurrent lines whole. */
    private fun append(path: String, line: String) {
        val handle = fopen(path, "a") ?: return
        fputs(line, handle)
        fclose(handle)
    }
}
