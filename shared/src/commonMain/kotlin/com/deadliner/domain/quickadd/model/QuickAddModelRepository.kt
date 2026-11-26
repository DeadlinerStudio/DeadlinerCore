package com.deadliner.domain.quickadd.model

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Streaming download into a private cache. It is never called by the synchronous preview. */
class QuickAddModelRepository internal constructor(
    private val files: QuickAddModelFileStore,
    private val providedClient: HttpClient? = null,
) {
    constructor(cacheDirectory: String) : this(createQuickAddModelFileStore(cacheDirectory.trimEnd('/') + "/quick-add-models"))
    private val mutex = Mutex()
    private val client = lazy {
        providedClient ?: HttpClient {
            // GitHub Release assets redirect to its CDN. Never downgrade HTTPS to HTTP.
            followRedirects = true
            install(HttpRedirect) { allowHttpsDowngrade = false }
            install(HttpTimeout) {
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 30000
                requestTimeoutMillis = 180000
            }
        }
    }

    @Throws(Exception::class)
    suspend fun download(source: QuickAddModelSource, progress: (Long, Long) -> Unit = { _, _ -> }): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            source.validate()
            if (validCache(source)) return@withLock files.path(source.cacheName)
            progress(0, source.sizeBytes)
            val temporary = source.cacheName + "." + Random.nextLong().toULong().toString(16) + ".part"
            try {
                client.value.prepareGet(source.url).execute { response ->
                    check(response.status.value == 200) { "Model HTTP status: ${response.status.value}" }
                    response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
                        check(it == source.sizeBytes) { "Model Content-Length does not match" }
                    }
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(16 * 1024)
                    val digest = ModelSha256()
                    val writer = files.writer(temporary)
                    var total = 0L
                    try {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = channel.readAvailable(buffer, 0, buffer.size)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            check(total <= source.sizeBytes) { "Model exceeds declared byte size" }
                            digest.update(buffer, count)
                            writer.write(buffer, count)
                            progress(total, source.sizeBytes)
                        }
                        check(total == source.sizeBytes && digest.hex().equals(source.sha256, true)) { "Model size or SHA-256 does not match" }
                    } finally { writer.close() }
                }
                currentCoroutineContext().ensureActive()
                files.promote(temporary, source.cacheName)
                files.path(source.cacheName)
            } finally { files.delete(temporary) }
        }
    }

    private suspend fun validCache(source: QuickAddModelSource): Boolean {
        if (!files.exists(source.cacheName)) return false
        val reader = files.reader(source.cacheName)
        val buffer = ByteArray(16 * 1024)
        val digest = ModelSha256()
        var total = 0L
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = reader.read(buffer)
                if (count <= 0) break
                total += count
                if (total > source.sizeBytes) return false
                digest.update(buffer, count)
            }
            return total == source.sizeBytes && digest.hex().equals(source.sha256, true)
        } finally { reader.close() }
    }

    /** Close only after cancelling/joining outstanding prepare work. Caller-owned clients stay open. */
    fun close() { if (providedClient == null && client.isInitialized()) client.value.close() }
}
