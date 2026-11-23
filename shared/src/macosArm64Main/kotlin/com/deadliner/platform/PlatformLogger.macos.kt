package com.deadliner.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSLog
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getpid
import platform.posix.mkdir
import platform.Foundation.NSUUID
import platform.posix.EEXIST
import platform.posix.errno

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
    private val sessionId = NSUUID().UUIDString.lowercase().replace("-", "")
    private var diagnosticsDirectory: String? = null
    private var processId: Long? = null

    actual fun configure(directory: String, processId: Long?) {
        val diagnostics = directory.trimEnd('/') + "/Diagnostics"
        val available = mkdir(diagnostics, 0x1EDu) == 0 || errno == EEXIST
        this.processId = processId ?: getpid().toLong()
        diagnosticsDirectory = diagnostics.takeIf { available }
    }

    actual fun write(level: LogLevel, category: String, message: String, errorMessage: String?) {
        writeWithFallback(level, category, message, errorMessage) { text -> NSLog(text.replace("%", "%%")) }
    }

    internal fun writeWithFallback(
        level: LogLevel,
        category: String,
        message: String,
        errorMessage: String?,
        systemLog: (String) -> Unit,
    ) {
        val directory = diagnosticsDirectory
        val written = directory?.let {
            val record = PlatformLogFormat.record(level, category, message, sessionId, processId, errorMessage)
            val jsonWritten = append("$it/deadliner-diagnostics.jsonl", PlatformLogFormat.jsonLine(record))
            val textWritten = append("$it/deadliner-diagnostics.log", PlatformLogFormat.standardLine(record))
            jsonWritten && textWritten
        } ?: false
        if (!written || level == LogLevel.WARN || level == LogLevel.ERROR) {
            val errorSuffix = errorMessage?.takeIf { it.isNotBlank() }?.let { ": " + it }.orEmpty()
            systemLog("[${level.name}] $category: $message$errorSuffix")
        }
    }

    private fun append(path: String, line: String): Boolean {
        val handle = fopen(path, "a") ?: return false
        val written = fputs(line, handle)
        return fclose(handle) == 0 && written >= 0
    }
}
