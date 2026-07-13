package com.attriax.sdk.internal.request

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire-shape tests for the deep-link resolve + dynamic-link request builders.
 * Grounds the payloads in the api DTOs
 * (`SdkV1DeepLinkResolveDto` / `SdkCreateDynamicLinkDto`) — unknown props are
 * rejected by whitelist validation, so absent optionals must be OMITTED.
 */
class AttriaxDeepLinkRequestBuildersTest {

    @Test
    fun resolveRequestHasWireFieldsAndOmitsAbsentOptionals() {
        val request = AttriaxRequestBuilders.buildResolveDeepLink(
            projectToken = "tok",
            platform = "android",
            source = "attriax_sdk",
            isFirstLaunch = true,
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
            rawUrl = "https://sub.attriax.com/p?a=1",
            linkPath = "p",
            sessionId = null,
            sessionRelativeTimeMs = null,
            metadata = mapOf("isInitialLink" to true),
        )

        assertEquals(AttriaxApiRequest.KIND_RESOLVE_DEEP_LINK, request.kind)
        assertEquals(AttriaxEndpoints.DEEP_LINKS_RESOLVE, request.path)

        val body = request.body
        assertEquals("tok", body["projectToken"])
        assertEquals("android", body["platform"])
        assertEquals("dev-1", body["deviceId"])
        assertEquals("android_ssaid", body["deviceIdSource"])
        assertEquals("https://sub.attriax.com/p?a=1", body["rawUrl"])
        assertEquals("p", body["linkPath"])
        assertEquals("attriax_sdk", body["source"])
        assertEquals(true, body["isFirstLaunch"])
        assertEquals(mapOf("isInitialLink" to true), body["metadata"])
        // Absent optionals omitted, not sent as null (whitelist validation).
        assertFalse(body.containsKey("sessionId"))
        assertFalse(body.containsKey("sessionRelativeTimeMs"))
    }

    @Test
    fun resolveRequestOmitsIdentityWhenAnonymous() {
        val request = AttriaxRequestBuilders.buildResolveDeepLink(
            projectToken = "tok",
            platform = "android",
            source = "attriax_sdk",
            isFirstLaunch = false,
            deviceId = null,
            deviceIdSource = null,
            rawUrl = "myapp://open",
            linkPath = "open",
            sessionId = null,
            sessionRelativeTimeMs = null,
            metadata = null,
        )
        val body = request.body
        assertFalse(body.containsKey("deviceId"))
        assertFalse(body.containsKey("deviceIdSource"))
        assertFalse(body.containsKey("metadata"))
        assertEquals("tok", body["projectToken"])
    }

    @Test
    fun dynamicLinkBodyFlattensRedirectsPreviewAndUtms() {
        val body = AttriaxRequestBuilders.buildCreateDynamicLink(
            projectToken = "tok",
            name = "Summer",
            destinationUrl = "https://example.com/promo",
            group = "campaigns",
            prefix = "sum",
            iosRedirect = true,
            androidRedirect = false,
            previewTitle = "Title",
            previewDescription = "Desc",
            utmSource = "newsletter",
            utmMedium = "email",
            utmCampaign = "summer",
            utmTerm = null,
            utmContent = null,
            data = mapOf("screen" to "promo"),
        )

        assertEquals("tok", body["projectToken"])
        assertEquals("Summer", body["name"])
        assertEquals("https://example.com/promo", body["destinationUrl"])
        assertEquals("campaigns", body["group"])
        assertEquals("sum", body["prefix"])
        assertEquals(true, body["iosRedirect"])
        assertEquals(false, body["androidRedirect"])
        assertEquals("Title", body["previewTitle"])
        assertEquals("Desc", body["previewDescription"])
        assertEquals("newsletter", body["utmSource"])
        assertEquals("email", body["utmMedium"])
        assertEquals("summer", body["utmCampaign"])
        assertEquals(mapOf("screen" to "promo"), body["data"])
        // Absent optionals omitted.
        assertFalse(body.containsKey("utmTerm"))
        assertFalse(body.containsKey("utmContent"))
    }

    @Test
    fun deepLinkResolveIsTerminalDropExempt() {
        val request = AttriaxRequestBuilders.buildResolveDeepLink(
            projectToken = "tok",
            platform = "android",
            source = "attriax_sdk",
            isFirstLaunch = true,
            deviceId = null,
            deviceIdSource = null,
            rawUrl = "https://sub.attriax.com/p",
            linkPath = "p",
            sessionId = null,
            sessionRelativeTimeMs = null,
            metadata = null,
        )
        // Row DL5: deep-link resolve is exempt from the terminal-drop retry policy.
        assertTrue(request.isTerminalDropExempt)
        assertFalse(request.isBatchable)
    }
}
