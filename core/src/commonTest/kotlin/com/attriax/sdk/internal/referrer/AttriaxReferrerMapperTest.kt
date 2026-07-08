package com.attriax.sdk.internal.referrer

import com.attriax.sdk.AttributionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure mapping from the app-open attribution object to [com.attriax.sdk.AttriaxInstallReferrerDetails]
 * (mirrors Flutter `AttriaxInstallReferrerDetails.fromJson`). Locks the wire field
 * names, null semantics, deep-link URI parsing, and the `utm` convenience getter.
 */
class AttriaxReferrerMapperTest {

    @Test
    fun mapsAllFieldsFromWireObject() {
        val map = mapOf<String, Any?>(
            "rawPlatformInstallReferrer" to "utm_source=google",
            "source" to "google",
            "medium" to "cpc",
            "campaign" to "spring",
            "term" to "shoes",
            "content" to "banner",
            "adNetwork" to "google_ads",
            "adClickId" to "gclid123",
            "attributionType" to "external",
            "deepLinkUrl" to "https://sub.attriax.com/promo",
            "deepLinkData" to mapOf("k" to "v"),
            "registeredAt" to "2026-07-08T00:00:00Z",
            "installBeginTimestampSeconds" to 1234L,
            "referrerClickTimestampSeconds" to 1200L,
            "googlePlayInstantParam" to true,
            "precision" to 0.75,
        )

        val details = AttriaxReferrerMapper.installReferrerDetailsFromMap(map)

        assertEquals("utm_source=google", details.rawPlatformInstallReferrer)
        assertEquals("google", details.source)
        assertEquals("cpc", details.medium)
        assertEquals("spring", details.campaign)
        assertEquals("shoes", details.term)
        assertEquals("banner", details.content)
        assertEquals("google_ads", details.adNetwork)
        assertEquals("gclid123", details.adClickId)
        assertEquals(AttributionType.EXTERNAL, details.attributionType)
        assertEquals("https://sub.attriax.com/promo", details.deepLinkUrl)
        // deepLinkUri falls back to deepLinkUrl and is parsed.
        assertEquals("https://sub.attriax.com/promo", details.deepLinkUri?.toString())
        assertEquals(mapOf("k" to "v"), details.deepLinkData)
        assertEquals("2026-07-08T00:00:00Z", details.registeredAt)
        assertEquals(1234L, details.installBeginTimestampSeconds)
        assertEquals(1200L, details.referrerClickTimestampSeconds)
        assertEquals(true, details.googlePlayInstantParam)
        assertEquals(0.75, details.precision)
        assertEquals(mapOf("source" to "google", "medium" to "cpc", "campaign" to "spring", "term" to "shoes", "content" to "banner"), details.utm)
    }

    @Test
    fun defaultsAndNullsForEmptyObject() {
        val details = AttriaxReferrerMapper.installReferrerDetailsFromMap(emptyMap<String, Any?>())

        assertEquals(AttributionType.ORGANIC, details.attributionType)
        assertEquals(0.0, details.precision)
        assertNull(details.source)
        assertNull(details.deepLinkUri)
        assertNull(details.deepLinkData)
        assertNull(details.installBeginTimestampSeconds)
        assertNull(details.utm)
    }
}
