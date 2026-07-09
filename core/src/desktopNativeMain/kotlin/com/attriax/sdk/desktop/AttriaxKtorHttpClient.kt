package com.attriax.sdk.desktop

import com.attriax.sdk.internal.AttriaxHttpException
import com.attriax.sdk.internal.AttriaxTimeoutException
import com.attriax.sdk.internal.AttriaxTransportException
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.json.Json
import io.ktor.client.HttpClient as KtorHttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse as KtorHttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Kotlin/Native desktop HTTP transport backed by Ktor. The engine is chosen per
 * target from that target's own source set — WinHttp on mingwX64 (self-contained on
 * Windows), Curl on linuxX64 — and picked up automatically from the classpath, so
 * this shared native code stays engine-agnostic.
 *
 * Ktor is suspend-based but the [HttpClient] port `post` is SYNCHRONOUS, so each
 * call bridges through [runBlocking]. This class replicates the wire contract of
 * the Android [com.attriax.sdk.android.AttriaxOkHttpClient] and JVM
 * [com.attriax.sdk.jvm.AttriaxJvmHttpClient] EXACTLY (the transport is the only wire
 * boundary, so any drift here is a real incident):
 *
 *  - stamps the mandatory real [userAgent] on EVERY request (load-bearing: the
 *    generator default trips the backend isbot filter and a drifting UA fragments
 *    anonymous identity),
 *  - `Content-Type: application/json`, request/connect/socket timeouts from
 *    [requestTimeoutMs],
 *  - treats 2xx as success and unwraps the `{data: ...}` response envelope,
 *  - maps non-2xx → [AttriaxHttpException] (status + body + headers), Ktor
 *    timeout exceptions → [AttriaxTimeoutException], any other transport/IO failure
 *    → [AttriaxTransportException], matching what the retry policy classifies,
 *  - header-map first-wins (parity with the JVM client's `values.firstOrNull()`).
 *
 * One instance per SDK runtime — the Ktor client (and its connection pool) is
 * shared across all requests.
 */
class AttriaxKtorHttpClient(
    private val baseUrl: String,
    private val userAgent: String,
    private val requestTimeoutMs: Long,
) : HttpClient {

    private val client: KtorHttpClient = KtorHttpClient {
        // Do not throw on non-2xx — we classify status codes ourselves below, exactly
        // like the OkHttp / HttpURLConnection paths.
        expectSuccess = false
        if (requestTimeoutMs > 0L) {
            install(HttpTimeout) {
                requestTimeoutMillis = requestTimeoutMs
                connectTimeoutMillis = requestTimeoutMs
                socketTimeoutMillis = requestTimeoutMs
            }
        }
    }

    override fun post(path: String, body: String): HttpResponse = runBlocking {
        val response: KtorHttpResponse = try {
            client.post(joinUrl(baseUrl, path)) {
                header("User-Agent", userAgent)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: HttpRequestTimeoutException) {
            throw AttriaxTimeoutException(cause = e)
        } catch (e: ConnectTimeoutException) {
            throw AttriaxTimeoutException(cause = e)
        } catch (e: SocketTimeoutException) {
            throw AttriaxTimeoutException(cause = e)
        } catch (e: CancellationException) {
            // Never swallow structured-concurrency cancellation.
            throw e
        } catch (e: Throwable) {
            // Any other failure (DNS, connection refused, TLS, IO) is a retryable
            // transport failure — the same bucket OkHttp's IOException maps to.
            throw AttriaxTransportException(cause = e)
        }

        val statusCode = response.status.value
        val headers = response.headers.toSingleValueMap()
        val successful = statusCode in 200..299

        val rawBody: String? = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Reading the body failed: for a 2xx that is a transport failure; for a
            // non-2xx keep null so the exception still carries status + headers
            // (parity with the JVM client).
            if (successful) throw AttriaxTransportException(cause = e) else null
        }

        if (!successful) {
            throw AttriaxHttpException(
                statusCode = statusCode,
                responseBody = rawBody,
                headers = headers,
            )
        }

        HttpResponse(
            statusCode = statusCode,
            body = unwrapEnvelope(rawBody),
            headers = headers,
        )
    }

    /** Unwrap `{ "data": <value> }` → the re-encoded `<value>`; pass through otherwise. */
    private fun unwrapEnvelope(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        return try {
            val decoded = Json.decode(raw)
            if (decoded is Map<*, *> && decoded.containsKey("data")) {
                Json.encode(decoded["data"])
            } else {
                raw
            }
        } catch (e: Exception) {
            raw
        }
    }

    /**
     * Flatten Ktor [Headers] to a single-value map, first value wins per name
     * (matches the JVM client's `values.firstOrNull()` and the port's
     * case-insensitive first-wins [HttpResponse.header] lookup).
     */
    private fun Headers.toSingleValueMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for ((key, values) in entries()) {
            values.firstOrNull()?.let { map[key] = it }
        }
        return map
    }

    private fun joinUrl(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return "$b$p"
    }
}
