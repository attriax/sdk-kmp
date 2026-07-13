package com.attriax.sdk.internal.dispatch

import com.attriax.sdk.internal.request.AttriaxApiRequest
import kotlin.math.abs
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Describes a delivery failure independently of the transport implementation,
 * so the retry policy is pure and unit-testable.
 */
sealed interface AttriaxFailure {
    /** A non-2xx HTTP response. */
    data class Http(val statusCode: Int, val retryAfter: String? = null) : AttriaxFailure

    /** A request timeout (always retryable). */
    data object Timeout : AttriaxFailure

    /** Any other transport/IO failure (always retryable). */
    data object Transport : AttriaxFailure
}

/**
 * Retry / backoff / terminal-drop policy (
 * Flutter `dispatch/request_retry_policy.dart`).
 *
 *  - Retryable: HTTP 408/425/429/≥500, plus timeout & transport errors.
 *    Every other 4xx is dropped.
 *  - Backoff: `Retry-After` wins; else capped exponential (base 2s, doubling,
 *    cap 5min) with a deterministic ±20% jitter derived from `attemptedAt` (no RNG).
 *  - Terminal drop: attemptCount ≥ 8 → `max_attempts_exceeded`;
 *    age > 7 days → `max_age_exceeded`. Deep-link resolves are EXEMPT.
 */
object AttriaxRetryPolicy {
    const val MAX_RETRY_ATTEMPTS = 8
    const val MAX_RETRY_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    const val BASE_BACKOFF_MS = 2_000L
    const val MAX_BACKOFF_MS = 5L * 60 * 1000 // 5 minutes

    const val REASON_MAX_ATTEMPTS = "max_attempts_exceeded"
    const val REASON_MAX_AGE = "max_age_exceeded"

    fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500

    fun isRetryable(failure: AttriaxFailure): Boolean = when (failure) {
        is AttriaxFailure.Http -> isRetryableHttpStatus(failure.statusCode)
        AttriaxFailure.Timeout -> true
        AttriaxFailure.Transport -> true
    }

    fun errorClass(failure: AttriaxFailure): String = when (failure) {
        is AttriaxFailure.Http -> "http_${failure.statusCode}"
        AttriaxFailure.Timeout -> "timeout"
        AttriaxFailure.Transport -> "transport"
    }

    fun httpStatusCode(failure: AttriaxFailure): Int? =
        (failure as? AttriaxFailure.Http)?.statusCode

    /**
     * Capped exponential backoff with deterministic jitter.
     *
     * @param attemptCount post-increment attempt number (1 after the first failure).
     * @param attemptedAtMs epoch millis of this attempt (drives the jitter, no RNG).
     * @return the absolute epoch-millis time of the next retry.
     */
    fun backoffRetryAtMs(attemptedAtMs: Long, attemptCount: Int): Long {
        val exponent = (attemptCount - 1).coerceIn(0, 20)
        val scaledMs = BASE_BACKOFF_MS * (1L shl exponent)
        val cappedMs = minOf(MAX_BACKOFF_MS, scaledMs)
        val jitterRange = (cappedMs * 0.2).toLong()
        val jitterMs = if (jitterRange == 0L) 0L else abs(attemptedAtMs) % (jitterRange + 1)
        return attemptedAtMs + cappedMs + jitterMs
    }

    /**
     * Resolve the `Retry-After` header to an absolute retry time, or null when
     * absent/non-positive/unparseable (caller then falls back to backoff).
     * Supports delta-seconds and the HTTP-date format.
     */
    fun retryAfterAtMs(failure: AttriaxFailure, attemptedAtMs: Long): Long? {
        val http = failure as? AttriaxFailure.Http ?: return null
        val raw = http.retryAfter?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val seconds = raw.toLongOrNull()
        if (seconds != null) {
            // Non-positive delay gives no useful spacing → fall back to backoff.
            if (seconds <= 0) return null
            return attemptedAtMs + seconds * 1000
        }

        val dateMs = parseHttpDateMs(raw) ?: return null
        return if (dateMs > attemptedAtMs) dateMs else null
    }

    /**
     * Compute the next-retry time for a failed request: `Retry-After` wins, else
     * jittered backoff.
     */
    fun nextRetryAtMs(failure: AttriaxFailure, attemptedAtMs: Long, nextAttemptCount: Int): Long =
        retryAfterAtMs(failure, attemptedAtMs) ?: backoffRetryAtMs(attemptedAtMs, nextAttemptCount)

    /**
     * Terminal-drop reason for a queued request, or null if it should stay queued.
     * Deep-link resolves are exempt from terminal drop.
     */
    fun terminalDropReason(request: AttriaxApiRequest, attemptCount: Int, createdAtMs: Long, nowMs: Long): String? {
        if (request.isTerminalDropExempt) return null
        if (attemptCount >= MAX_RETRY_ATTEMPTS) return REASON_MAX_ATTEMPTS
        if (nowMs - createdAtMs > MAX_RETRY_AGE_MS) return REASON_MAX_AGE
        return null
    }

    // RFC 7231 IMF-fixdate: "Sun, 06 Nov 1994 08:49:37 GMT"
    private val httpDatePattern =
        Regex("""^[A-Za-z]{3}, (\d{2}) ([A-Za-z]{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$""")

    private val months = mapOf(
        "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4, "May" to 5, "Jun" to 6,
        "Jul" to 7, "Aug" to 8, "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12,
    )

    private fun parseHttpDateMs(value: String): Long? {
        val m = httpDatePattern.matchEntire(value) ?: return null
        val month = months[m.groupValues[2]] ?: return null
        return try {
            LocalDateTime(
                year = m.groupValues[3].toInt(),
                monthNumber = month,
                dayOfMonth = m.groupValues[1].toInt(),
                hour = m.groupValues[4].toInt(),
                minute = m.groupValues[5].toInt(),
                second = m.groupValues[6].toInt(),
                nanosecond = 0,
            ).toInstant(TimeZone.UTC).toEpochMilliseconds()
        } catch (e: Exception) {
            null
        }
    }
}
