package com.deadliner.platform

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 平台日志抽象。
 *
 * 各平台 actual 实现：
 * - iOS: os.Logger
 * - Android: android.util.Log
 * - HarmonyOS: hilog
 */
expect class PlatformLogger() {
    /** Debug 级别日志 */
    fun d(tag: String, message: String)

    /** Info 级别日志 */
    fun i(tag: String, message: String)

    /** Warning 级别日志 */
    fun w(tag: String, message: String)

    /** Error 级别日志 */
    fun e(tag: String, message: String, error: Throwable? = null)
}

/** 日志级别（密封类，编译时类型安全） */
enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

/**
 * Host-configured writer for KMP diagnostic records.
 *
 * Android and HarmonyOS configure it with their private files directory during
 * application startup. The iOS host keeps stdout as its source because the
 * existing process-wide stdout capture feeds AppLog.
 */
expect object PlatformLogSink {
    fun configure(directory: String, processId: Long? = null)

    fun write(
        level: LogLevel,
        category: String,
        message: String,
        errorMessage: String? = null,
    )
}

/** JSONL schema shared with iOS `AppLogRecord`. */
@Serializable
data class PlatformLogRecord(
    val schemaVersion: Int = 1,
    val timestamp: Double,
    val sessionID: String,
    val processID: Long? = null,
    val level: String,
    val category: String,
    val message: String,
    val context: Map<String, String>? = null,
)

/** Shared serialization and text projection so KMP records match iOS exports. */
internal object PlatformLogFormat {
    private val json = Json {
        encodeDefaults = false
    }

    fun now(): Double = Clock.System.now().toEpochMilliseconds().toDouble() / 1_000.0

    fun jsonLine(record: PlatformLogRecord): String = json.encodeToString(record) + "\n"

    fun standardLine(record: PlatformLogRecord, source: String = "KMP"): String {
        val eventName = record.message
            .takeIf { it.startsWith("[") && it.endsWith("]") }
            ?.drop(1)
            ?.dropLast(1)
        val module = eventName ?: record.category
        val message = if (eventName == null) normalized(record.message) else ""
        val level = if (record.level == "info") "" else " level=" + record.level
        val fields = record.context
            ?.keys
            ?.sorted()
            ?.joinToString(" ") { key -> key + "=" + normalized(record.context[key].orEmpty()) }
            .orEmpty()
        val suffix = listOf(message, fields).filter { it.isNotEmpty() }.joinToString(" ")
        val timestamp = Instant.fromEpochMilliseconds((record.timestamp * 1_000.0).toLong()).toString()
        return "[" + timestamp + "][" + source + "][" + module + "]" + level +
            if (suffix.isEmpty()) "\n" else " " + suffix + "\n"
    }

    fun record(
        level: LogLevel,
        category: String,
        message: String,
        sessionId: String,
        processId: Long?,
        errorMessage: String?,
    ): PlatformLogRecord {
        val context = errorMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { mapOf("error" to it) }
        return PlatformLogRecord(
            timestamp = now(),
            sessionID = sessionId,
            processID = processId,
            level = level.name.lowercase(),
            category = category,
            message = message,
            context = context,
        )
    }

    private fun normalized(value: String): String = value
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}
