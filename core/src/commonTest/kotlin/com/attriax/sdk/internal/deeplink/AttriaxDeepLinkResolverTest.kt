package com.attriax.sdk.internal.deeplink

import com.attriax.sdk.AttriaxDeepLinkResolutionStatus
import com.attriax.sdk.AttriaxDeepLinkTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure deep-link resolver tests (PARITY §6, rows DL2/DL4): link-path normalization,
 * query-parameter metadata, isInitialLink flag, and resolution-status mapping.
 */
class AttriaxDeepLinkResolverTest {

    // -------- linkPath normalization (row DL2) --------

    @Test
    fun normalizeStripsLeadingAndTrailingSlashes() {
        assertEquals("promo/summer", AttriaxDeepLinkResolver.normalizeLinkPath("/promo/summer/"))
        assertEquals("promo/summer", AttriaxDeepLinkResolver.normalizeLinkPath("///promo/summer///"))
        assertEquals("promo", AttriaxDeepLinkResolver.normalizeLinkPath("promo"))
    }

    @Test
    fun normalizeCollapsesEmptyOrSlashOnlyToNull() {
        assertNull(AttriaxDeepLinkResolver.normalizeLinkPath(null))
        assertNull(AttriaxDeepLinkResolver.normalizeLinkPath(""))
        assertNull(AttriaxDeepLinkResolver.normalizeLinkPath("   "))
        assertNull(AttriaxDeepLinkResolver.normalizeLinkPath("/"))
        assertNull(AttriaxDeepLinkResolver.normalizeLinkPath("////"))
    }

    @Test
    fun extractLinkPathFromHttpsPrefersPathThenHost() {
        val uri = AttriaxUri.parse("https://sub.attriax.com/promo/summer?x=1")!!
        assertEquals("promo/summer", AttriaxDeepLinkResolver.extractLinkPathFromUri(uri))
    }

    @Test
    fun extractLinkPathFromHttpsFallsBackToHostWhenPathEmpty() {
        val uri = AttriaxUri.parse("https://sub.attriax.com/")!!
        assertEquals("sub.attriax.com", AttriaxDeepLinkResolver.extractLinkPathFromUri(uri))
    }

    @Test
    fun extractLinkPathFromCustomSchemeJoinsHostAndPath() {
        val uri = AttriaxUri.parse("myapp://open/product/42")!!
        assertEquals("open/product/42", AttriaxDeepLinkResolver.extractLinkPathFromUri(uri))
    }

    // -------- query params -> metadata + isInitialLink (row DL2) --------

    @Test
    fun buildResolveMetadataCarriesIsInitialLinkAndQueryParams() {
        val uri = AttriaxUri.parse("https://sub.attriax.com/p?a=1&a=2&b=x")!!
        val metadata = AttriaxDeepLinkResolver.buildResolveMetadata(uri, isInitialLink = true)

        assertEquals(true, metadata["isInitialLink"])
        @Suppress("UNCHECKED_CAST")
        val qp = metadata["queryParameters"] as Map<String, List<String>>
        assertEquals(listOf("1", "2"), qp["a"])
        assertEquals(listOf("x"), qp["b"])
    }

    @Test
    fun buildResolveMetadataMergesCallerMetadataAndOverridesInitialFlag() {
        val uri = AttriaxUri.parse("myapp://open/thing")!!
        val metadata = AttriaxDeepLinkResolver.buildResolveMetadata(
            uri,
            isInitialLink = false,
            extra = mapOf("campaign" to "email", "isInitialLink" to true),
        )
        assertEquals("email", metadata["campaign"])
        // The manager-controlled isInitialLink wins over any caller value.
        assertEquals(false, metadata["isInitialLink"])
    }

    // -------- resolution status mapping (row DL4) --------

    @Test
    fun statusMappingCoversAllWireValues() {
        assertEquals(AttriaxDeepLinkResolutionStatus.MATCHED, AttriaxDeepLinkResolutionStatus.fromWire("matched"))
        assertEquals(AttriaxDeepLinkResolutionStatus.UNMATCHED, AttriaxDeepLinkResolutionStatus.fromWire("unmatched"))
        assertEquals(AttriaxDeepLinkResolutionStatus.INVALID, AttriaxDeepLinkResolutionStatus.fromWire("invalid"))
        // Unknown/absent defaults to unmatched (safe: not a confirmed match).
        assertEquals(AttriaxDeepLinkResolutionStatus.UNMATCHED, AttriaxDeepLinkResolutionStatus.fromWire(null))
        assertEquals(AttriaxDeepLinkResolutionStatus.UNMATCHED, AttriaxDeepLinkResolutionStatus.fromWire("weird"))
    }

    @Test
    fun decodeResolutionReadsMatchedFoundAndBrowserAction() {
        val data = mapOf(
            "matched" to true,
            "status" to "matched",
            "isFirstLaunch" to false,
            "deepLink" to mapOf(
                "path" to "promo/summer",
                "uri" to "https://sub.attriax.com/promo/summer",
                "data" to mapOf("k" to "v"),
            ),
            "browserAction" to mapOf("url" to "https://x.com", "openMode" to "external"),
        )
        val result = AttriaxDeepLinkResolver.decodeResolution(data)
        assertTrue(result.matched)
        assertEquals(AttriaxDeepLinkResolutionStatus.MATCHED, result.status)
        assertEquals("promo/summer", result.path)
        assertEquals(mapOf("k" to "v"), result.data)
        assertEquals("https://x.com", result.browserAction?.url)
    }

    @Test
    fun buildResolutionUsesCanonicalUriAndTrigger() {
        val fallback = AttriaxUri.parse("https://sub.attriax.com/promo")!!
        val result = AttriaxDeepLinkResolver.decodeResolution(
            mapOf(
                "matched" to true,
                "status" to "matched",
                "deepLink" to mapOf("uri" to "https://sub.attriax.com/promo/summer"),
            ),
        )
        val event = AttriaxDeepLinkResolver.buildResolution(
            result = result,
            clickedAtMs = 100,
            consumedAtMs = 200,
            trigger = AttriaxDeepLinkTrigger.COLD_START,
            fallbackUri = fallback,
        )
        assertEquals("https://sub.attriax.com/promo/summer", event.uri.toString())
        assertTrue(event.found)
        assertTrue(event.isAttriaxSubDomain)
        assertEquals(AttriaxDeepLinkTrigger.COLD_START, event.trigger)
    }

    @Test
    fun isAttriaxDomainMatchesSubdomainsOnly() {
        assertTrue(AttriaxDeepLinkResolver.isAttriaxDomain(AttriaxUri.parse("https://acme.attriax.com/x")!!))
        assertFalse(AttriaxDeepLinkResolver.isAttriaxDomain(AttriaxUri.parse("https://example.com/x")!!))
        assertFalse(AttriaxDeepLinkResolver.isAttriaxDomain(AttriaxUri.parse("myapp://open/thing")!!))
    }
}
