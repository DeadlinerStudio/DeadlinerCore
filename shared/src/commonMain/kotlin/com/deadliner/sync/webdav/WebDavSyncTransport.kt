package com.deadliner.sync.webdav

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.ContentType
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.deadliner.sync.transport.SyncBlob
import com.deadliner.sync.transport.SyncBlobTransport
import com.deadliner.sync.transport.SyncTransportHttpException
import com.deadliner.sync.transport.SyncTransportParentCreationException
import com.deadliner.sync.transport.SyncTransportPreconditionFailedException
import com.deadliner.sync.transport.SyncWriteCondition

/** Platform-owned secret values supplied for one sync run; never persisted by KMP. */
data class WebDavCredentials(
    val username: String? = null,
    val password: String? = null,
)

data class WebDavTransportConfiguration(
    val baseUrl: String,
    val credentials: WebDavCredentials = WebDavCredentials(),
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    init {
        require(baseUrl.trim().startsWith("http://") || baseUrl.trim().startsWith("https://")) {
            "WebDAV base URL must be an absolute HTTP(S) URL."
        }
        require(requestTimeoutMillis > 0) { "WebDAV request timeout must be positive." }
    }

    internal companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L
    }
}

/**
 * Common Ktor implementation of the WebDAV subset used by Deadliner's sync
 * protocols. Ktor engines are supplied by each platform source set.
 */
@OptIn(ExperimentalEncodingApi::class)
class WebDavSyncTransport(
    private val configuration: WebDavTransportConfiguration,
    private val client: HttpClient = HttpClient {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = configuration.requestTimeoutMillis
        }
    },
) : SyncBlobTransport {
    override suspend fun read(path: String): SyncBlob? {
        val head = request(path, HttpMethod.Head)
        if (head.status.value in MISSING_STATUS_CODES) return null
        requireSuccess(head.status.value, "HEAD $path")

        val response = request(path, HttpMethod.Get)
        if (response.status != HttpStatusCode.OK) {
            throw SyncTransportHttpException(response.status.value, "GET $path")
        }
        return SyncBlob(
            bytes = response.body(),
            version = response.headers[HttpHeaders.ETag] ?: head.headers[HttpHeaders.ETag],
        )
    }

    override suspend fun write(path: String, bytes: ByteArray, condition: SyncWriteCondition) {
        if (!ensureParentCollections(path)) {
            throw SyncTransportParentCreationException(path)
        }

        val response = request(path, HttpMethod.Put) {
            contentType(ContentType.Application.Json)
            when (condition) {
                SyncWriteCondition.CreateOnly -> header(HttpHeaders.IfNoneMatch, "*")
                is SyncWriteCondition.MatchVersion -> header(HttpHeaders.IfMatch, condition.value)
                SyncWriteCondition.Unconditional -> Unit
            }
            setBody(bytes)
        }
        if (response.status.value == HttpStatusCode.PreconditionFailed.value) {
            throw SyncTransportPreconditionFailedException()
        }
        requireSuccess(response.status.value, "PUT $path")
    }

    override suspend fun ensureCollection(path: String): Boolean {
        val normalized = normalizeCollectionPath(path)
        if (normalized.isEmpty()) return true
        val response = request(
            normalized,
            HttpMethod("PROPFIND"),
            headers = mapOf("Depth" to "0", HttpHeaders.ContentType to ContentType.Application.Xml.toString()),
        )
        if (response.status.value in EXISTING_COLLECTION_STATUS_CODES) return true

        val create = request(normalized, HttpMethod("MKCOL"))
        return create.status.value in CREATED_COLLECTION_STATUS_CODES
    }

    override fun close() {
        client.close()
    }

    private suspend fun ensureParentCollections(filePath: String): Boolean {
        val parent = normalizeFilePath(filePath).substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isEmpty()) return true
        var current = ""
        for (segment in parent.split('/').filter(String::isNotEmpty)) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            if (!ensureCollection(current)) return false
        }
        return true
    }

    private suspend fun request(
        path: String,
        method: HttpMethod,
        headers: Map<String, String> = emptyMap(),
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): io.ktor.client.statement.HttpResponse = client.request(joinUrl(path)) {
        this.method = method
        basicAuthorizationHeader()?.let { header(HttpHeaders.Authorization, it) }
        headers.forEach { (name, value) -> header(name, value) }
        configure()
    }

    private fun joinUrl(path: String): String =
        configuration.baseUrl.trim().trimEnd('/') + "/" + normalizeFilePath(path)

    private fun normalizeFilePath(path: String): String = path.trim().trimStart('/')

    private fun normalizeCollectionPath(path: String): String = normalizeFilePath(path).trimEnd('/') + "/"

    private fun requireSuccess(statusCode: Int, operation: String) {
        if (statusCode !in 200..299) throw SyncTransportHttpException(statusCode, operation)
    }

    private fun basicAuthorizationHeader(): String? {
        val username = configuration.credentials.username ?: return null
        val password = configuration.credentials.password ?: return null
        if (username.isEmpty() && password.isEmpty()) return null
        return "Basic " + Base64.Default.encode("$username:$password".encodeToByteArray())
    }

    private companion object {
        val MISSING_STATUS_CODES = setOf(404, 409, 410)
        val EXISTING_COLLECTION_STATUS_CODES = setOf(200, 207, 301, 302)
        val CREATED_COLLECTION_STATUS_CODES = setOf(201, 405, 409)
    }
}
