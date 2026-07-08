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
