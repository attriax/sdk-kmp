package com.attriax.sdk

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Factory smoke test: [AttriaxDesktop.create] assembles a working engine, [Attriax.init]
 * bootstraps without throwing, and the resulting app-open POST rides the real
 * (isbot-passing) desktop User-Agent plus a non-empty platform/osVersion context.
 */
class AttriaxDesktopFactoryTest {

    private lateinit var server: HttpServer
    private lateinit var dataDir: File
    private var port: Int = 0

    private val capturedUserAgent = AtomicReference<String>()
    private val capturedBody = AtomicReference<String>()
    private val capturedPath = AtomicReference<String>()
    private val requestLatch = CountDownLatch(1)

    @BeforeTest
    fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/") { exchange ->
            capturedPath.set(exchange.requestURI.path)
            capturedUserAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
            capturedBody.set(exchange.requestBody.readBytes().decodeToString())
            val bytes = """{"data":{}}""".encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            requestLatch.countDown()
        }
        server.start()

        dataDir = File.createTempFile("attriax-desktop", "").let {
            it.delete(); it.mkdirs(); it
        }
    }

    @AfterTest
    fun teardown() {
        server.stop(0)
        dataDir.deleteRecursively()
    }

    @Test
    fun createReturnsAnEngineThatInitsAndPostsAnAppOpenWithARealUserAgent() {
        val config = AttriaxConfig(
            projectToken = "tok_test",
            apiBaseUrl = "http://127.0.0.1:$port",
            appPackageName = "com.example.desktop",
            appVersion = "1.2.3",
        )

        val attriax = AttriaxDesktop.create(config, dataDir)
        // init() must not throw.
        attriax.init()

        assertTrue(requestLatch.await(10, TimeUnit.SECONDS), "an app-open POST was attempted")

        val ua = capturedUserAgent.get()
        assertNotNull(ua)
        // isbot-shape check: a real SDK UA, NOT the generator default.
        assertTrue(!ua.startsWith("OpenAPI-Generator"), "UA is not the generator default")
        assertTrue(ua.startsWith("attriax-jvm-sdk/"), "UA carries the desktop client slug: $ua")
        assertTrue(ua.contains("(") && ua.contains(")"), "UA carries the mandatory parenthetical: $ua")
        assertTrue(ua.contains("com.example.desktop"), "UA descriptor is the package name: $ua")

        val body = capturedBody.get()
        assertNotNull(body)
        // Captured context: a non-empty platform slug + osVersion ride the open.
        assertTrue(
            Regex(""""platform":"(windows|macos|linux|jvm)"""").containsMatchIn(body),
            "open body carries a non-empty platform slug: $body",
        )
        assertTrue(body.contains("\"osVersion\""), "open body carries osVersion: $body")
    }
}
