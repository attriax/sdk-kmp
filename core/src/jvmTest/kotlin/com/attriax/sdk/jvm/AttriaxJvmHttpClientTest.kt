package com.attriax.sdk.jvm

import com.attriax.sdk.internal.AttriaxHttpException
import com.attriax.sdk.internal.AttriaxTimeoutException
import com.attriax.sdk.internal.AttriaxTransportException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real embedded-server coverage for [AttriaxJvmHttpClient] — asserts the wire
 * contract it must share with the Android OkHttp transport: request stamping,
 * `{data:...}` envelope unwrap, and the typed exception mapping the retry policy
 * classifies.
 */
class AttriaxJvmHttpClientTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    private fun baseUrl(): String = "http://127.0.0.1:$port"

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.executor = null
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    private fun HttpExchange.respond(code: Int, body: String, header: Pair<String, String>? = null) {
        val bytes = body.encodeToByteArray()
        header?.let { responseHeaders.add(it.first, it.second) }
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    @Test
    fun sendsBodyContentTypeAndUserAgentAndUnwrapsEnvelope() {
        val capturedBody = AtomicReference<String>()
        val capturedContentType = AtomicReference<String>()
        val capturedUserAgent = AtomicReference<String>()
        val capturedMethod = AtomicReference<String>()

        server.createContext("/api/echo") { exchange ->
            capturedMethod.set(exchange.requestMethod)
            capturedContentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            capturedUserAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            capturedBody.set(exchange.requestBody.readBytes().decodeToString())
            exchange.respond(200, """{"data":{"ok":true,"n":7}}""")
        }

        val client = AttriaxJvmHttpClient(
            baseUrl = baseUrl(),
            userAgent = "attriax-jvm-sdk/0.6.0 (Windows 11 10.0; com.example.app)",
            requestTimeoutMs = 5_000L,
        )

        val response = client.post("/api/echo", """{"event":"open"}""")

        assertEquals("POST", capturedMethod.get())
        assertEquals("""{"event":"open"}""", capturedBody.get())
        assertEquals("application/json", capturedContentType.get())
        assertEquals("attriax-jvm-sdk/0.6.0 (Windows 11 10.0; com.example.app)", capturedUserAgent.get())
        assertEquals(200, response.statusCode)
        // The `{data:...}` envelope is unwrapped to the inner value, re-encoded.
        assertEquals("""{"ok":true,"n":7}""", response.body)
    }

    @Test
    fun passesThroughABodyWithoutADataEnvelope() {
        server.createContext("/api/raw") { exchange ->
            exchange.respond(200, """{"noEnvelope":1}""")
        }
        val client = AttriaxJvmHttpClient(baseUrl(), "attriax-jvm-sdk/0.6.0 (x; y)", 5_000L)

        val response = client.post("/api/raw", "{}")

        assertEquals("""{"noEnvelope":1}""", response.body)
    }

    @Test
    fun mapsNon2xxToHttpExceptionWithBodyAndHeaders() {
        server.createContext("/api/fail") { exchange ->
            exchange.respond(500, """{"error":"boom"}""", header = "X-Attriax-Test" to "yes")
        }
        val client = AttriaxJvmHttpClient(baseUrl(), "attriax-jvm-sdk/0.6.0 (x; y)", 5_000L)

        val ex = assertFailsWith<AttriaxHttpException> {
            client.post("/api/fail", "{}")
        }
        assertEquals(500, ex.statusCode)
        assertNotNull(ex.responseBody)
        assertTrue(ex.responseBody!!.contains("boom"))
        // Header map is exposed case-insensitively via HttpResponse.header semantics.
        val headerVal = ex.headers.entries.firstOrNull { it.key.equals("X-Attriax-Test", ignoreCase = true) }?.value
        assertEquals("yes", headerVal)
    }

    @Test
    fun mapsASlowEndpointBeyondTheTimeoutToTimeoutException() {
        server.createContext("/api/slow") { exchange ->
            Thread.sleep(2_000L)
            exchange.respond(200, """{"data":{}}""")
        }
        val client = AttriaxJvmHttpClient(baseUrl(), "attriax-jvm-sdk/0.6.0 (x; y)", 300L)

        assertFailsWith<AttriaxTimeoutException> {
            client.post("/api/slow", "{}")
        }
    }

    @Test
    fun mapsConnectionRefusedToTransportException() {
        // Grab a port, close it → nothing is listening → connection refused.
        val socket = ServerSocket(0)
        val deadPort = socket.localPort
        socket.close()

        val client = AttriaxJvmHttpClient("http://127.0.0.1:$deadPort", "attriax-jvm-sdk/0.6.0 (x; y)", 2_000L)

        assertFailsWith<AttriaxTransportException> {
            client.post("/api/x", "{}")
        }
    }
}
