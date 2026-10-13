package com.deadliner.platform

import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Android PlatformLogger actual 实现。
 *
 * 始终镜像到 Logcat；宿主完成 configure 后，KMP 记录也会写入与原生日志
 * 相同的 Diagnostics 文件。
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

actual object PlatformLogSink {
    private val lock = Any()
    private val sessionId = UUID.randomUUID().toString().lowercase()
    private var diagnosticsDirectory: File? = null
    private var processId: Long? = null
    private var fileWriter: ((String, String) -> Unit)? = null

    /** Android hosts can serialize Native/KMP writes and retention on one IO queue. */
    fun configureFileWriter(writer: ((String, String) -> Unit)?) {
        synchronized(lock) { fileWriter = writer }
    }

    actual fun configure(directory: String, processId: Long?) {
        synchronized(lock) {
            diagnosticsDirectory = File(directory, "Diagnostics").also { it.mkdirs() }
            this.processId = processId
        }
    }

    actual fun write(level: LogLevel, category: String, message: String, errorMessage: String?) {
        val record = PlatformLogFormat.record(level, category, message, sessionId, processId, errorMessage)
        synchronized(lock) {
            writeToFiles(record)
        }
        val output = message + errorMessage?.takeIf { it.isNotBlank() }?.let { ": " + it }.orEmpty()
        when (level) {
            LogLevel.DEBUG -> Log.d(category, output)
            LogLevel.INFO -> Log.i(category, output)
            LogLevel.WARN -> Log.w(category, output)
            LogLevel.ERROR -> Log.e(category, output)
        }
    }

    private fun writeToFiles(record: PlatformLogRecord) {
        val directory = diagnosticsDirectory ?: return
        runCatching {
            fileWriter?.let { writer ->
                writer(PlatformLogFormat.jsonLine(record), PlatformLogFormat.standardLine(record))
                return
            }
            File(directory, "deadliner-diagnostics.jsonl").appendText(PlatformLogFormat.jsonLine(record))
            File(directory, "deadliner-diagnostics.log").appendText(PlatformLogFormat.standardLine(record))
        }
    }
}
