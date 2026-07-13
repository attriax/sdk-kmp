package com.attriax.sdk.android

import com.attriax.sdk.internal.AttriaxHttpException
import com.attriax.sdk.internal.AttriaxTimeoutException
import com.attriax.sdk.internal.AttriaxTransportException
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * The single long-lived OkHttp-backed transport.
 *
 * Responsibilities:
 *  - stamp the mandatory real User-Agent on EVERY request (load-bearing: the
 *    bare form / generator default trips the backend isbot filter and a drifting
 *    UA fragments anonymous identity),
 *  - `Content-Type: application/json`, config timeouts,
 *  - treat 2xx as success and unwrap the `{data: ...}` response envelope,
 *  - map non-2xx / timeout / transport failures to the typed exceptions the
 *    retry policy classifies.
 *
 * One instance per SDK runtime — the `OkHttpClient` connection pool and the
 * stable UA are shared across all requests.
 */
class AttriaxOkHttpClient(
    private val baseUrl: String,
    private val userAgent: String,
    requestTimeoutMs: Long,
    certificatePinner: okhttp3.CertificatePinner? = null,
) : HttpClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        .apply { if (certificatePinner != null) certificatePinner(certificatePinner) }
        .build()

    override fun post(path: String, body: String): HttpResponse {
        val request = Request.Builder()
            .url(joinUrl(baseUrl, path))
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return execute(request)
    }

    override fun get(path: String): HttpResponse {
        val request = Request.Builder()
            .url(joinUrl(baseUrl, path))
            .header("User-Agent", userAgent)
            .header("Content-Type", "application/json")
            .get()
            .build()
        return execute(request)
    }

    private fun execute(request: Request): HttpResponse {
        val response = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw AttriaxTimeoutException(cause = e)
        } catch (e: InterruptedException) {
            throw AttriaxTimeoutException(cause = e)
        } catch (e: IOException) {
            throw AttriaxTransportException(cause = e)
        }

        response.use {
            val headers = it.headers.toMap()
            val rawBody = it.body?.string()
            if (!it.isSuccessful) {
                throw AttriaxHttpException(
                    statusCode = it.code,
                    responseBody = rawBody,
                    headers = headers,
                )
            }
            return HttpResponse(
                statusCode = it.code,
                body = unwrapEnvelope(rawBody),
                headers = headers,
            )
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

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (i in 0 until size) map[name(i)] = value(i)
        return map
    }

    private fun joinUrl(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return "$b$p"
    }
}
