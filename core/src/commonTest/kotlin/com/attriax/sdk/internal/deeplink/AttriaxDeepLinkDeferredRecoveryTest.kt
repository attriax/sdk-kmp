package com.attriax.sdk.internal.deeplink

import com.attriax.sdk.AttriaxDeepLinkTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure deferred-recovery tests (PARITY §6, row DL3): source PREFERENCE order
 * (deepLink > reinstall > install), the appDataClear skip, and the found flag.
 */
class AttriaxDeepLinkDeferredRecoveryTest {

    @Test
    fun prefersDeepLinkUriOverReferrers() {
        val data = mapOf<String, Any?>(
            "deepLink" to mapOf("uri" to "https://sub.attriax.com/from-deeplink"),
            "reinstallReferrer" to mapOf("deepLinkUri" to "https://sub.attriax.com/from-reinstall"),
            "installReferrer" to mapOf("deepLinkUri" to "https://sub.attriax.com/from-install"),
        )
        val event = AttriaxDeepLinkDeferredRecovery.recover(data, 1_000L)!!
        assertEquals("https://sub.attriax.com/from-deeplink", event.uri.toString())
        assertEquals(AttriaxDeepLinkTrigger.DEFERRED, event.trigger)
        // A concrete deepLink object → confirmed match.
        assertTrue(event.found)
    }

    @Test
    fun fallsBackToReinstallReferrerWhenNoDeepLink() {
        val data = mapOf<String, Any?>(
            "reinstallReferrer" to mapOf("deepLinkUri" to "https://sub.attriax.com/from-reinstall"),
            "installReferrer" to mapOf("deepLinkUri" to "https://sub.attriax.com/from-install"),
        )
        val event = AttriaxDeepLinkDeferredRecovery.recover(data, 1_000L)!!
        assertEquals("https://sub.attriax.com/from-reinstall", event.uri.toString())
        // No concrete deepLink object → not a confirmed match.
        assertFalse(event.found)
    }

    @Test
    fun fallsBackToInstallReferrerLast() {
        val data = mapOf<String, Any?>(
            "installReferrer" to mapOf("deepLinkUri" to "https://sub.attriax.com/from-install"),
        )
        val event = AttriaxDeepLinkDeferredRecovery.recover(data, 1_000L)!!
        assertEquals("https://sub.attriax.com/from-install", event.uri.toString())
    }

    @Test
    fun skipsOnAppDataClearInstallState() {
        val data = mapOf<String, Any?>(
            "installState" to "appDataClear",
            "deepLink" to mapOf("uri" to "https://sub.attriax.com/x"),
        )
        assertNull(AttriaxDeepLinkDeferredRecovery.recover(data, 1_000L))
    }

    @Test
    fun returnsNullWhenNothingRecoverable() {
        assertNull(AttriaxDeepLinkDeferredRecovery.recover(null, 1_000L))
        assertNull(AttriaxDeepLinkDeferredRecovery.recover(emptyMap(), 1_000L))
        assertNull(
            AttriaxDeepLinkDeferredRecovery.recover(
                mapOf("installReferrer" to mapOf("source" to "google")),
                1_000L,
            ),
        )
    }

    @Test
    fun parsesIsoServerClickAndConsumeTimestamps() {
        // Backend serializes deferred click/consume as ISO-8601 strings; they must be
        // parsed to their real epoch-millis, NOT collapsed to the recovery-time-now.
        val data = mapOf<String, Any?>(
            "deepLink" to mapOf("uri" to "https://sub.attriax.com/x"),
            "deepLinkClickedAt" to "1994-11-06T08:49:37.700Z",
            "deepLinkConsumedAt" to "1994-11-06T08:49:38.000Z",
        )
        val event = AttriaxDeepLinkDeferredRecovery.recover(data, fallbackTimeMs = 9_999L)!!
        assertEquals(784_111_777_700L, event.clickedAtMs)
        assertEquals(784_111_778_000L, event.consumedAtMs)
    }

    @Test
    fun fallsBackToAcceptedAtIsoThenNowForTimestamps() {
        // No click/consume, but an ISO acceptedAt → both timestamps take acceptedAt.
        val withAccepted = mapOf<String, Any?>(
            "deepLink" to mapOf("uri" to "https://sub.attriax.com/x"),
            "acceptedAt" to "1994-11-06T08:49:37.005Z",
        )
        val a = AttriaxDeepLinkDeferredRecovery.recover(withAccepted, fallbackTimeMs = 9_999L)!!
        assertEquals(784_111_777_005L, a.clickedAtMs)
        assertEquals(784_111_777_005L, a.consumedAtMs)

        // Nothing parseable → the now-fallback is used for both.
        val absent = mapOf<String, Any?>(
            "deepLink" to mapOf("uri" to "https://sub.attriax.com/x"),
            "deepLinkClickedAt" to "not-a-date",
        )
        val b = AttriaxDeepLinkDeferredRecovery.recover(absent, fallbackTimeMs = 9_999L)!!
        assertEquals(9_999L, b.clickedAtMs)
        assertEquals(9_999L, b.consumedAtMs)
    }

    @Test
    fun stillHonorsNumericTimestamps() {
        val data = mapOf<String, Any?>(
            "deepLink" to mapOf("uri" to "https://sub.attriax.com/x"),
            "deepLinkClickedAt" to 1_234L,
            "deepLinkConsumedAt" to 5_678,
        )
        val event = AttriaxDeepLinkDeferredRecovery.recover(data, fallbackTimeMs = 9_999L)!!
        assertEquals(1_234L, event.clickedAtMs)
        assertEquals(5_678L, event.consumedAtMs)
    }

    @Test
    fun carriesReferrerUtmWhenNoDeepLink() {
        val data = mapOf<String, Any?>(
            "reinstallReferrer" to mapOf(
                "deepLinkUri" to "https://sub.attriax.com/r",
                "source" to "newsletter",
                "medium" to "email",
            ),
        )
        val event = AttriaxDeepLinkDeferredRecovery.recover(data, 1_000L)!!
        assertEquals("newsletter", event.utm?.get("source"))
        assertEquals("email", event.utm?.get("medium"))
    }
}
