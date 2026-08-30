package com.deadliner.sync.server.transport

import com.deadliner.sync.server.model.ServerSyncPullResponse
import com.deadliner.sync.server.model.ServerSyncPushRequest
import com.deadliner.sync.server.model.ServerSyncPushResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

/** Secrets are supplied by a host for one authenticated transport lifetime and are never persisted. */
data class DeadlinerServerTransportConfiguration(
    val baseUrl: String,
    val accessToken: String,
    val deviceUid: String,
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    init {
        val normalizedBaseUrl = baseUrl.trim()
        require(normalizedBaseUrl.startsWith("http://") || normalizedBaseUrl.startsWith("https://")) {
            "DeadlinerServer base URL must be an absolute HTTP(S) URL."
        }
        require(accessToken.isNotBlank()) { "DeadlinerServer access token must not be blank." }
        require(deviceUid.isNotBlank()) { "DeadlinerServer device UID must not be blank." }
        require(requestTimeoutMillis > 0) { "DeadlinerServer request timeout must be positive." }
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000L
    }
}

class KtorDeadlinerServerTransport(
    private val configuration: DeadlinerServerTransportConfiguration,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
    private val client: HttpClient = HttpClient {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = configuration.requestTimeoutMillis
        }
    },
) : DeadlinerServerTransport {
    override suspend fun push(request: ServerSyncPushRequest): ServerSyncPushResponse {
        require(request.deviceUid == configuration.deviceUid) {
            "Push device UID must match the authenticated transport device."
        }
        val response = client.request(endpoint("v2/sync/push")) {
            method = HttpMethod.Post
            authenticatedJsonHeaders()
            setBody(json.encodeToString(ServerSyncPushRequest.serializer(), request))
        }
        requireSuccess(response.status.value, "POST /v2/sync/push", response.headers[REQUEST_ID_HEADER])
        return json.decodeFromString(ServerSyncPushResponse.serializer(), response.bodyAsText())
    }

    override suspend fun pull(cursor: Long, limit: Int): ServerSyncPullResponse {
        require(cursor >= 0) { "Pull cursor must be non-negative." }
        require(limit > 0) { "Pull limit must be positive." }
        val response = client.request(endpoint("v2/sync/pull")) {
            method = HttpMethod.Get
            authenticatedJsonHeaders()
            url {
                parameters.append("device_uid", configuration.deviceUid)
                parameters.append("cursor", cursor.toString())
                parameters.append("limit", limit.toString())
            }
        }
        requireSuccess(response.status.value, "GET /v2/sync/pull", response.headers[REQUEST_ID_HEADER])
        return json.decodeFromString(ServerSyncPullResponse.serializer(), response.bodyAsText())
    }

    override fun close() {
        client.close()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authenticatedJsonHeaders() {
        header(HttpHeaders.Authorization, "Bearer ${configuration.accessToken}")
        accept(ContentType.Application.Json)
        contentType(ContentType.Application.Json)
    }

    private fun endpoint(path: String): String = configuration.baseUrl.trim().trimEnd('/') + "/" + path

    private fun requireSuccess(statusCode: Int, operation: String, requestId: String?) {
        if (statusCode in 200..299) return
        throw DeadlinerServerHttpException(statusCode, operation, requestId)
    }

    private companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
    }
}

class DeadlinerServerHttpException(
    val statusCode: Int,
    operation: String,
    val requestId: String? = null,
) : IllegalStateException(
    buildString {
        append("DeadlinerServer HTTP ")
        append(statusCode)
        append(": ")
        append(operation)
        requestId?.let {
            append(" (requestId=")
            append(it)
            append(')')
        }
    },
)
