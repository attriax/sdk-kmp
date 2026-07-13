package com.attriax.sdk.internal.dispatch

import com.attriax.sdk.internal.request.AttriaxApiRequest
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Retry classification, backoff / Retry-After, terminal drop + DL exemption. */
class AttriaxRetryPolicyTest {

    // ---- Q2: retryable classification ----

    @Test
    fun retryableStatusesMatchContract() {
        assertTrue(AttriaxRetryPolicy.isRetryableHttpStatus(408))
        assertTrue(AttriaxRetryPolicy.isRetryableHttpStatus(425))
        assertTrue(AttriaxRetryPolicy.isRetryableHttpStatus(429))
        assertTrue(AttriaxRetryPolicy.isRetryableHttpStatus(500))
        assertTrue(AttriaxRetryPolicy.isRetryableHttpStatus(503))
    }

    @Test
    fun otherClientErrorsAreNotRetryable() {
        assertFalse(AttriaxRetryPolicy.isRetryableHttpStatus(400))
        assertFalse(AttriaxRetryPolicy.isRetryableHttpStatus(401))
        assertFalse(AttriaxRetryPolicy.isRetryableHttpStatus(403))
        assertFalse(AttriaxRetryPolicy.isRetryableHttpStatus(404))
        assertFalse(AttriaxRetryPolicy.isRetryableHttpStatus(422))
    }

    @Test
    fun timeoutAndTransportAlwaysRetryable() {
        assertTrue(AttriaxRetryPolicy.isRetryable(AttriaxFailure.Timeout))
        assertTrue(AttriaxRetryPolicy.isRetryable(AttriaxFailure.Transport))
        assertTrue(AttriaxRetryPolicy.isRetryable(AttriaxFailure.Http(429)))
        assertFalse(AttriaxRetryPolicy.isRetryable(AttriaxFailure.Http(400)))
    }

    @Test
    fun errorClassNaming() {
        assertEquals("http_500", AttriaxRetryPolicy.errorClass(AttriaxFailure.Http(500)))
        assertEquals("timeout", AttriaxRetryPolicy.errorClass(AttriaxFailure.Timeout))
        assertEquals("transport", AttriaxRetryPolicy.errorClass(AttriaxFailure.Transport))
    }

    // ---- Q3: backoff + jitter ----

    @Test
    fun backoffDoublesFromBaseAndCaps() {
        val at = 0L
        // attempt 1 → 2s base; jitter is 0 at attemptedAt=0.
        assertEquals(2_000L, AttriaxRetryPolicy.backoffRetryAtMs(at, 1))
        // attempt 2 → 4s, attempt 3 → 8s ...
        assertEquals(4_000L, AttriaxRetryPolicy.backoffRetryAtMs(at, 2))
        assertEquals(8_000L, AttriaxRetryPolicy.backoffRetryAtMs(at, 3))
        // Large attempt caps at 5 minutes.
        assertEquals(5L * 60 * 1000, AttriaxRetryPolicy.backoffRetryAtMs(at, 20))
    }

    @Test
    fun jitterIsDeterministicAndWithinTwentyPercent() {
        val attemptedAt = 1_700_000_000_123L
        val delay = AttriaxRetryPolicy.backoffRetryAtMs(attemptedAt, 1) - attemptedAt
        // base 2s, jitter in [0, 0.2*2000] = [0, 400]
        assertTrue(delay in 2_000L..2_400L, "delay=$delay")
        // Deterministic: same inputs → same output.
        assertEquals(delay, AttriaxRetryPolicy.backoffRetryAtMs(attemptedAt, 1) - attemptedAt)
    }

    @Test
    fun retryAfterSecondsWinsOverBackoff() {
        val at = 1_000L
        val next = AttriaxRetryPolicy.nextRetryAtMs(AttriaxFailure.Http(429, retryAfter = "30"), at, 1)
        assertEquals(at + 30_000L, next)
    }

    @Test
    fun nonPositiveRetryAfterFallsBackToBackoff() {
        val at = 0L
        val next = AttriaxRetryPolicy.nextRetryAtMs(AttriaxFailure.Http(429, retryAfter = "0"), at, 1)
        assertEquals(2_000L, next) // backoff base, not immediate
    }

    @Test
    fun retryAfterHttpDateParsed() {
        val at = 0L
        val result = AttriaxRetryPolicy.retryAfterAtMs(
            AttriaxFailure.Http(503, retryAfter = "Sun, 06 Nov 1994 08:49:37 GMT"), at,
        )
        // 784111777 seconds since epoch.
        assertEquals(784_111_777_000L, result)
    }

    @Test
    fun missingRetryAfterYieldsNull() {
        assertNull(AttriaxRetryPolicy.retryAfterAtMs(AttriaxFailure.Http(500), 0L))
        assertNull(AttriaxRetryPolicy.retryAfterAtMs(AttriaxFailure.Timeout, 0L))
    }

    // ---- Q4: terminal drop + deep-link exemption ----

    private fun request(kind: String) =
        AttriaxApiRequest(kind, AttriaxEndpoints.EVENTS, mapOf("projectToken" to "t"))

    @Test
    fun dropsAfterEightAttempts() {
        val r = request(AttriaxApiRequest.KIND_TRACK_EVENT)
        assertEquals(AttriaxRetryPolicy.REASON_MAX_ATTEMPTS, AttriaxRetryPolicy.terminalDropReason(r, 8, 0L, 0L))
        assertNull(AttriaxRetryPolicy.terminalDropReason(r, 7, 0L, 0L))
    }

    @Test
    fun dropsAfterSevenDays() {
        val r = request(AttriaxApiRequest.KIND_TRACK_EVENT)
        val eightDays = 8L * 24 * 60 * 60 * 1000
        assertEquals(AttriaxRetryPolicy.REASON_MAX_AGE, AttriaxRetryPolicy.terminalDropReason(r, 0, 0L, eightDays))
        val sixDays = 6L * 24 * 60 * 60 * 1000
        assertNull(AttriaxRetryPolicy.terminalDropReason(r, 0, 0L, sixDays))
    }

    @Test
    fun deepLinkResolveIsExemptFromTerminalDrop() {
        val dl = AttriaxApiRequest(
            AttriaxApiRequest.KIND_RESOLVE_DEEP_LINK, AttriaxEndpoints.DEEP_LINKS_RESOLVE, emptyMap(),
        )
        val tenDays = 10L * 24 * 60 * 60 * 1000
        assertNull(AttriaxRetryPolicy.terminalDropReason(dl, 100, 0L, tenDays))
    }
}
