package com.deadliner.sync.webdav

import com.deadliner.sync.transport.SyncTransportPreconditionFailedException
import com.deadliner.sync.transport.SyncWriteCondition
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WebDavSyncTransportTest {
    @Test
    fun `read uses the configured base path authentication and ETag`() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val transport = transport { request ->
            requests += request
            when (request.method) {
                HttpMethod.Head -> respond(
                    content = "",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ETag, "\"version-1\""),
                )
                HttpMethod.Get -> respond(content = "payload", status = HttpStatusCode.OK)
                else -> error("Unexpected ${request.method}")
            }
        }

        val blob = transport.read("/Deadliner/snapshot-v2.json")

        assertEquals("payload", blob?.bytes?.decodeToString())
        assertEquals("\"version-1\"", blob?.version)
        assertEquals("https://dav.example/root/Deadliner/snapshot-v2.json", requests.first().url.toString())
        assertEquals("Basic dXNlcjpwYXNz", requests.first().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `read treats a missing remote payload as empty`() = runBlocking {
        val transport = transport { request ->
            assertEquals(HttpMethod.Head, request.method)
            respond(content = "", status = HttpStatusCode.NotFound)
        }

        assertNull(transport.read("Deadliner/habit-snapshot-v2.json"))
    }

    @Test
    fun `write creates its parent collection and sends the ETag condition`() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val transport = transport { request ->
            requests += request
            when (request.method) {
                HttpMethod("PROPFIND") -> respond(content = "", status = HttpStatusCode.NotFound)
                HttpMethod("MKCOL") -> respond(content = "", status = HttpStatusCode.Created)
                HttpMethod.Put -> respond(content = "", status = HttpStatusCode.Created)
                else -> error("Unexpected ${request.method}")
            }
        }

        transport.write(
            path = "Deadliner/category-snapshot-v2.json",
            bytes = "{}".encodeToByteArray(),
            condition = SyncWriteCondition.MatchVersion("\"version-2\""),
        )

        assertEquals(listOf(HttpMethod("PROPFIND"), HttpMethod("MKCOL"), HttpMethod.Put), requests.map { it.method })
        assertEquals("0", requests[0].headers["Depth"])
        assertEquals("\"version-2\"", requests.last().headers[HttpHeaders.IfMatch])
    }

    @Test
    fun `write exposes an ETag conflict for the facade retry policy`() = runBlocking {
        val transport = transport { request ->
            when (request.method) {
                HttpMethod("PROPFIND") -> respond(content = "", status = HttpStatusCode.NotFound)
                HttpMethod("MKCOL") -> respond(content = "", status = HttpStatusCode.Created)
                HttpMethod.Put -> respond(content = "", status = HttpStatusCode.PreconditionFailed)
                else -> error("Unexpected ${request.method}")
            }
        }

        assertFailsWith<SyncTransportPreconditionFailedException> {
            transport.write(
                path = "Deadliner/snapshot-v2.json",
                bytes = "{}".encodeToByteArray(),
                condition = SyncWriteCondition.CreateOnly,
            )
        }
        Unit
    }

    private fun transport(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): WebDavSyncTransport = WebDavSyncTransport(
        configuration = WebDavTransportConfiguration(
            baseUrl = "https://dav.example/root",
            credentials = WebDavCredentials(username = "user", password = "pass"),
        ),
        client = HttpClient(MockEngine(handler)) { expectSuccess = false },
    )
}
