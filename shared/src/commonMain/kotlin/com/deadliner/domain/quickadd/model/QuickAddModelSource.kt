package com.deadliner.domain.quickadd.model

import io.ktor.http.URLProtocol
import io.ktor.http.Url

enum class QuickAddModelFormat(val extension: String) { ONNX("onnx"), MINDSPORE("ms"), CORE_ML("mlmodel") }

/** Provided by trusted app configuration; no production URL/model has been chosen yet. */
data class QuickAddModelSource(
    val modelId: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val format: QuickAddModelFormat,
) {
    internal fun validate() {
        require(Regex("[A-Za-z0-9._-]{1,80}").matches(modelId)) { "Invalid model version ID" }
        require(Url(url).protocol == URLProtocol.HTTPS) { "Model download requires HTTPS" }
        require(Regex("[a-fA-F0-9]{64}").matches(sha256)) { "Model SHA-256 is required" }
        require(sizeBytes in 1..536870912L) { "Model byte size must be declared and at most 512 MiB" }
    }
    internal val cacheName: String get() = sha256.lowercase() + "." + format.extension
}

internal interface QuickAddModelReader { fun read(buffer: ByteArray): Int; fun close() }
internal interface QuickAddModelWriter { fun write(buffer: ByteArray, count: Int); fun close() }
internal interface QuickAddModelFileStore {
    fun path(name: String): String
    fun exists(name: String): Boolean
    fun reader(name: String): QuickAddModelReader
    fun writer(name: String): QuickAddModelWriter
    fun promote(temporary: String, destination: String)
    fun delete(name: String)
}
internal expect fun createQuickAddModelFileStore(directory: String): QuickAddModelFileStore
internal expect fun prepareQuickAddModelFile(path: String, format: QuickAddModelFormat): String
