package com.attriax.sdk.internal.request

import com.attriax.sdk.internal.AttriaxContextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PARITY row E4 — platform/version OMITTED from event & user payloads; carried on open. */
class AttriaxRequestBuildersTest {

    @Test
    fun eventOmitsPlatformAndVersion() {
        val event = AttriaxRequestBuilders.buildEvent(
            projectToken = "t", eventName = "purchase", eventData = mapOf("revenue" to 9.99),
            deviceId = "d", deviceIdSource = "android_ssaid",
            sessionId = "s", sessionRelativeTimeMs = 100, clientOccurredAtIso = "2026-01-01T00:00:00.000Z",
        )
        val body = event.body
        assertFalse(body.containsKey("platform"))
        assertFalse(body.containsKey("appVersion"))
        assertFalse(body.containsKey("appBuildNumber"))
        assertFalse(body.containsKey("sdkApiVersion"))
        assertEquals("purchase", body["eventName"])
        assertEquals("t", body["projectToken"])
    }

    @Test
    fun userUsesExternalFieldsAndOmitsPlatformVersionAndTimestamp() {
        val user = AttriaxRequestBuilders.buildUser(
            projectToken = "t", externalUserId = "u", externalUserName = "Ada",
            properties = mapOf("plan" to "pro"),
            deviceId = "d", deviceIdSource = "android_ssaid",
        )
        val body = user.body
        // Wire field names must match SdkUserDto.
        assertEquals("u", body["externalUserId"])
        assertEquals("Ada", body["externalUserName"])
        assertEquals(mapOf("plan" to "pro"), body["properties"])
        // These would be rejected by the DTO's whitelist validation.
        assertFalse(body.containsKey("userId"))
        assertFalse(body.containsKey("userProperties"))
        assertFalse(body.containsKey("clientOccurredAt"))
        assertFalse(body.containsKey("platform"))
        assertFalse(body.containsKey("appVersion"))
        assertEquals(AttriaxEndpoints.USERS, user.path)
    }

    @Test
    fun openNestsContextUnderSdkAppDevice() {
        val ctx = AttriaxContextSnapshot(
            packageName = "com.x", appVersion = "1.2.3", appBuildNumber = "45",
            deviceModel = "Pixel", deviceManufacturer = "Google", osVersion = "14",
            deviceTimezone = "UTC", deviceLocale = "en-US",
        )
        val open = AttriaxRequestBuilders.buildOpen(
            projectToken = "t", context = ctx, deviceId = "d", deviceIdSource = "android_ssaid",
            isFirstLaunch = true, sessionId = null, sessionStartedAtIso = null,
        )
        val body = open.body
        assertEquals("android", body["platform"])
        assertEquals(true, body["isFirstLaunch"])
        assertEquals("d", body["deviceId"])
        assertTrue(open.isAppOpen)

        // Context MUST be nested under sdk/app/device (SdkV1OpenDto), NOT flat —
        // the backend rejects unknown top-level properties (whitelist validation).
        assertFalse(body.containsKey("sdkApiVersion"))
        assertFalse(body.containsKey("appVersion"))
        assertFalse(body.containsKey("osVersion"))

        @Suppress("UNCHECKED_CAST")
        val sdk = body["sdk"] as Map<String, Any?>
        assertEquals("v1", sdk["apiVersion"])
        assertEquals("0.5.0", sdk["packageVersion"])

        @Suppress("UNCHECKED_CAST")
        val app = body["app"] as Map<String, Any?>
        assertEquals("1.2.3", app["version"])
        assertEquals("45", app["buildNumber"])
        assertEquals("com.x", app["packageName"])

        @Suppress("UNCHECKED_CAST")
        val device = body["device"] as Map<String, Any?>
        assertEquals("Pixel", device["model"])
        assertEquals("Google", device["manufacturer"])
        assertEquals("14", device["osVersion"])
        assertEquals("UTC", device["timezone"])
        assertEquals("en-US", device["language"])

        // sdkMetadata defaults to null → the `sdk.metadata` block is OMITTED.
        assertFalse(sdk.containsKey("metadata"))
    }

    @Test
    fun openAttachesSdkMetadataUnderSdkBlockWhenProvided() {
        val ctx = AttriaxContextSnapshot(
            packageName = "com.x", appVersion = "1.2.3", appBuildNumber = "45",
            deviceModel = "Pixel", deviceManufacturer = "Google", osVersion = "14",
            deviceTimezone = "UTC", deviceLocale = "en-US",
        )
        val open = AttriaxRequestBuilders.buildOpen(
            projectToken = "t", context = ctx, deviceId = "d", deviceIdSource = "android_ssaid",
            isFirstLaunch = true, sessionId = null, sessionStartedAtIso = null,
            sdkMetadata = mapOf("integration" to "unity", "wrapperVersion" to "2.1.0"),
        )

        @Suppress("UNCHECKED_CAST")
        val sdk = open.body["sdk"] as Map<String, Any?>
        // Wire path: sdk.metadata (mirrors Flutter AttriaxSdkSnapshot.metadata).
        assertEquals("v1", sdk["apiVersion"])
        assertEquals("0.5.0", sdk["packageVersion"])
        @Suppress("UNCHECKED_CAST")
        val metadata = sdk["metadata"] as Map<String, Any?>
        assertEquals("unity", metadata["integration"])
        assertEquals("2.1.0", metadata["wrapperVersion"])
    }
}
