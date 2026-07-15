package com.attriax.sdk.apple

import com.attriax.sdk.internal.AttriaxHttpException
import com.attriax.sdk.internal.AttriaxTimeoutException
import com.attriax.sdk.internal.AttriaxTransportException
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.json.Json
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.darwin.dispatch_time_t

/**
 * The single long-lived `NSURLSession`-backed Apple transport.
 *
 * Responsibilities (byte-for-byte wire parity with the Android OkHttp / JVM / desktop
 * Ktor transports — the transport is the only wire boundary, so any drift here is a
 * real incident):
 *  - stamp the mandatory real [userAgent] on EVERY request (load-bearing: a
 *    synthetic UA trips the backend isbot filter and a drifting UA fragments
 *    anonymous identity — see [AttriaxAppleUserAgent]),
 *  - `Content-Type: application/json`, request/resource timeouts from
 *    [requestTimeoutMs],
 *  - treat 2xx as success and unwrap the `{data: ...}` response envelope,
 *  - map non-2xx → [AttriaxHttpException] (status + body + headers), an
 *    `NSURLErrorTimedOut` → [AttriaxTimeoutException], any other transport/IO failure
 *    → [AttriaxTransportException], matching what the retry policy classifies,
 *  - header-map first-wins (parity with the JVM client's `values.firstOrNull()`).
 *
 * `post` blocks the calling thread on a semaphore (like the Swift reference and the
 * Android `execute()` path) so the dispatcher — which runs on its own background
 * thread, never the main thread — reasons about delivery sequentially. One instance
 * per SDK runtime; the session's connection reuse + stable UA are shared across all
 * requests.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class AttriaxAppleUrlSessionHttpClient(
    private val baseUrl: String,
    private val userAgent: String,
    requestTimeoutMs: Long,
) : HttpClient {

    /**
     * Upper bound for the blocking semaphore wait: the configured request timeout plus a
     * margin, so NSURLSession's own timeouts win in the normal case and this only trips
     * as the backstop if a completion handler never arrives at all.
     */
    private val waitTimeoutMs: Long =
        if (requestTimeoutMs > 0L) requestTimeoutMs + WAIT_TIMEOUT_MARGIN_MS else DEFAULT_WAIT_TIMEOUT_MS

    private val session: NSURLSession = run {
        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration
        if (requestTimeoutMs > 0L) {
            val seconds = requestTimeoutMs.toDouble() / 1000.0
            configuration.timeoutIntervalForRequest = seconds
            configuration.timeoutIntervalForResource = seconds
        }
        configuration.HTTPAdditionalHeaders = mapOf<Any?, Any?>("User-Agent" to userAgent)
        NSURLSession.sessionWithConfiguration(configuration)
    }

    override fun post(path: String, body: String): HttpResponse =
        send(path, method = "POST", body = body)

    override fun get(path: String): HttpResponse =
        send(path, method = "GET", body = null)

    private fun send(path: String, method: String, body: String?): HttpResponse {
        val url = NSURL.URLWithString(joinUrl(baseUrl, path))
            ?: throw AttriaxTransportException(message = "invalid URL")

        val request = NSMutableURLRequest(uRL = url)
        request.setHTTPMethod(method)
        // Belt-and-braces over the session default so the load-bearing UA is present.
        request.setValue(userAgent, forHTTPHeaderField = "User-Agent")
        request.setValue("application/json", forHTTPHeaderField = "Content-Type")
        request.setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
        if (body != null) request.setHTTPBody(body.toNSData())

        val semaphore = dispatch_semaphore_create(0)
        var resultData: NSData? = null
        var resultResponse: NSURLResponse? = null
        var resultError: NSError? = null

        val task = session.dataTaskWithRequest(request) { data, response, error ->
            resultData = data
            resultResponse = response
            resultError = error
            dispatch_semaphore_signal(semaphore)
        }
        task.resume()
        // Bounded wait. NSURLSession's own request/resource timeouts are the primary
        // guard, but they are the ONLY guard: an unbounded DISPATCH_TIME_FOREVER means a
        // completion handler that never arrives wedges the dispatcher's flush thread
        // permanently — the synchronization state never leaves SYNCHRONIZING and no later
        // flush can recover, since the queue's single flush thread is parked forever.
        // Time it out, cancel, and surface a retryable timeout instead (the same bounded
        // -wait discipline AttriaxAppleUserAgent already uses for its WKWebView probe).
        if (dispatch_semaphore_wait(semaphore, deadlineFromNow(waitTimeoutMs)) != 0L) {
            task.cancel()
            throw AttriaxTimeoutException(
                message = "Apple transport wait exceeded ${waitTimeoutMs}ms for $method $path",
            )
        }

        resultError?.let { error ->
            if (error.domain == NSURLErrorDomain && error.code == NSURLErrorTimedOut) {
                throw AttriaxTimeoutException(message = error.localizedDescription)
            }
            throw AttriaxTransportException(message = error.localizedDescription)
        }

        val httpResponse = resultResponse as? NSHTTPURLResponse
            ?: throw AttriaxTransportException(message = "non-HTTP response")

        val headers = httpResponse.headerMap()
        val rawBody = resultData?.toUtf8String()
        val status = httpResponse.statusCode.toInt()
        val successful = status in 200..299

        if (!successful) {
            throw AttriaxHttpException(
                statusCode = status,
                responseBody = rawBody,
                headers = headers,
            )
        }

        return HttpResponse(
            statusCode = status,
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

    /** Flatten the response headers to a single-value map (first value wins per name). */
    private fun NSHTTPURLResponse.headerMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for ((key, value) in allHeaderFields) {
            val k = key as? String ?: continue
            val v = value as? String ?: continue
            if (!map.containsKey(k)) map[k] = v
        }
        return map
    }

    private fun joinUrl(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return "$b$p"
    }

    private fun String.toNSData(): NSData {
        val bytes = encodeToByteArray()
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert())
        }
    }

    private fun NSData.toUtf8String(): String? =
        NSString.create(this, NSUTF8StringEncoding) as String?

    /** A `dispatch_time` deadline [ms] milliseconds from now. */
    private fun deadlineFromNow(ms: Long): dispatch_time_t =
        dispatch_time(DISPATCH_TIME_NOW, ms * NSEC_PER_MSEC)

    private companion object {
        /** Nanoseconds per millisecond, for `dispatch_time` deadlines. */
        const val NSEC_PER_MSEC: Long = 1_000_000L

        /**
         * Margin added to the configured request timeout before the backstop wait fires,
         * so NSURLSession's own timeout normally wins and this only trips when a
         * completion handler never arrives at all.
         */
        const val WAIT_TIMEOUT_MARGIN_MS: Long = 5_000L

        /** Backstop when no request timeout is configured (`requestTimeoutMs <= 0`). */
        const val DEFAULT_WAIT_TIMEOUT_MS: Long = 60_000L
    }
}
