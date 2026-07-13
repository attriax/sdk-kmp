package com.attriax.sdk.jvm

import com.attriax.sdk.internal.AttriaxHttpException
import com.attriax.sdk.internal.AttriaxTimeoutException
import com.attriax.sdk.internal.AttriaxTransportException
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/**
 * JVM-desktop HTTP transport backed by the JDK [HttpURLConnection] (pure JDK, no
 * third-party dependency). The Android sibling is
 * [com.attriax.sdk.android.AttriaxOkHttpClient]; this class replicates its wire
 * contract EXACTLY (the transport is the only wire boundary, so any drift here is
 * a real incident):
 *
 *  - stamps the mandatory real [userAgent] on EVERY request (load-bearing: the
 *    generator default trips the backend isbot filter and a drifting UA fragments
 *    anonymous identity),
 *  - `Content-Type: application/json`, connect + read timeouts from
 *    [requestTimeoutMs],
 *  - treats 2xx as success and unwraps the `{data: ...}` response envelope,
 *  - maps non-2xx → [AttriaxHttpException] (status + body + headers), timeout →
 *    [AttriaxTimeoutException], any other IO failure → [AttriaxTransportException],
 *    matching what the retry policy classifies.
 *
 * One instance per SDK runtime (like the shared OkHttp client).
 */
class AttriaxJvmHttpClient(
    private val baseUrl: String,
    private val userAgent: String,
    private val requestTimeoutMs: Long,
) : HttpClient {

    override fun post(path: String, body: String): HttpResponse =
        send(path, method = "POST", body = body)

    override fun get(path: String): HttpResponse =
        send(path, method = "GET", body = null)

    private fun send(path: String, method: String, body: String?): HttpResponse {
        val url = URI.create(joinUrl(baseUrl, path)).toURL()
        val connection = try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                doOutput = body != null
                // Guard against a slow endpoint: connect + read both honor the config
                // timeout, then surface as AttriaxTimeoutException (retryable), mirroring
                // OkHttp's call/connect/read timeouts.
                connectTimeout = requestTimeoutMs.coerceToIntTimeout()
                readTimeout = requestTimeoutMs.coerceToIntTimeout()
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: IOException) {
            throw AttriaxTransportException(cause = e)
        }

        try {
            if (body != null) {
                try {
                    connection.outputStream.use { it.write(body.encodeToByteArray()) }
                } catch (e: SocketTimeoutException) {
                    throw AttriaxTimeoutException(cause = e)
                } catch (e: IOException) {
                    throw AttriaxTransportException(cause = e)
                }
            }

            val statusCode = try {
                connection.responseCode
            } catch (e: SocketTimeoutException) {
                throw AttriaxTimeoutException(cause = e)
            } catch (e: IOException) {
                throw AttriaxTransportException(cause = e)
            }

            val headers = connection.headerFields.toMap()
            val successful = statusCode in 200..299

            // For 2xx read the body stream; otherwise read the error stream so the
            // exception carries the same body the OkHttp path exposes.
            val rawBody = try {
                val stream = if (successful) connection.inputStream else connection.errorStream
                stream?.readBytes()?.decodeToString()
            } catch (e: SocketTimeoutException) {
                throw AttriaxTimeoutException(cause = e)
            } catch (e: IOException) {
                if (successful) throw AttriaxTransportException(cause = e) else null
            }

            if (!successful) {
                throw AttriaxHttpException(
                    statusCode = statusCode,
                    responseBody = rawBody,
                    headers = headers,
                )
            }

            return HttpResponse(
                statusCode = statusCode,
                body = unwrapEnvelope(rawBody),
                headers = headers,
            )
        } finally {
            connection.disconnect()
        }
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
     * Flatten [HttpURLConnection.getHeaderFields] to a single-value map. HttpURLConnection
     * exposes the HTTP status line under a `null` key — drop it — and multi-valued
     * headers as a list; keep the first value (matches OkHttp's `Headers.value(i)`
     * first-wins for [HttpResponse.header] case-insensitive lookup).
     */
    private fun Map<String?, List<String>>.toMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for ((key, values) in this) {
            if (key == null) continue
            values.firstOrNull()?.let { map[key] = it }
        }
        return map
    }

    private fun Long.coerceToIntTimeout(): Int =
        if (this <= 0L || this > Int.MAX_VALUE) 0 else this.toInt()

    private fun joinUrl(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return "$b$p"
    }
}
