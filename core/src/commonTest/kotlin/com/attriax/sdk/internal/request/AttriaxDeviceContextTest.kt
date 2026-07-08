package com.attriax.sdk.internal.request

import com.attriax.sdk.AttriaxDeviceContext
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.withDeviceContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Device-context enrichment parity with Flutter's `DeviceContextDto`: the full
 * optional field set lands in the open `device` block at the exact wire names,
 * nulls are omitted, the required five always emit, and a wrapper-supplied
 * [AttriaxDeviceContext] threads through and wins over auto-capture.
 */
class AttriaxDeviceContextTest {

    private fun buildOpenDevice(ctx: AttriaxContextSnapshot): Map<String, Any?> {
        val open = AttriaxRequestBuilders.buildOpen(
            projectToken = "t", context = ctx, deviceId = "d", deviceIdSource = "android_ssaid",
            isFirstLaunch = true, sessionId = null, sessionStartedAtIso = null,
        )
        @Suppress("UNCHECKED_CAST")
        return open.body["device"] as Map<String, Any?>
    }

    @Test
    fun openEmitsFullDeviceEnrichmentAtFlutterWireNames() {
        val ctx = AttriaxContextSnapshot(
            packageName = "com.x", appVersion = "1.2.3", appBuildNumber = "45",
            deviceModel = "Pixel 8", deviceManufacturer = "Google", osVersion = "14",
            deviceTimezone = "UTC", deviceLocale = "en-US",
            deviceBrand = "google", deviceHardware = "shiba", deviceName = "shiba",
            deviceIsPhysical = true,
            screenWidth = 1080, screenHeight = 2400, screenResolution = "1080x2400",
            devicePixelRatio = 2.625, colorDepth = 24,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            deviceMetadata = mapOf("nativeContext" to mapOf("k" to "v")),
            advertisingId = "ad-123", androidId = "aid-456",
        )
        val device = buildOpenDevice(ctx)

        // Required five always present.
        assertEquals("Pixel 8", device["model"])
        assertEquals("Google", device["manufacturer"])
        assertEquals("14", device["osVersion"])
        assertEquals("UTC", device["timezone"])
        assertEquals("en-US", device["language"])

        // Optional enrichment — exact DeviceContextDto wire names.
        assertEquals("google", device["brand"])
        assertEquals("shiba", device["hardware"])
        assertEquals("shiba", device["name"])
        assertEquals(true, device["isPhysicalDevice"])
        assertEquals(1080, device["screenWidth"])
        assertEquals(2400, device["screenHeight"])
        assertEquals("1080x2400", device["screenResolution"])
        assertEquals(2.625, device["devicePixelRatio"])
        assertEquals(24, device["colorDepth"])
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), device["supportedAbis"])
        assertEquals(mapOf("nativeContext" to mapOf("k" to "v")), device["metadata"])
        assertEquals("ad-123", device["advertisingId"])
        assertEquals("aid-456", device["androidId"])
    }

    @Test
    fun openOmitsNullAndEmptyEnrichmentButKeepsRequiredFive() {
        // Only the required five populated; every enrichment field left at default null,
        // plus an explicitly empty abis/metadata to prove the empty-omit rule.
        val ctx = AttriaxContextSnapshot(
            packageName = "com.x", appVersion = "1.2.3", appBuildNumber = "45",
            deviceModel = "Pixel", deviceManufacturer = "Google", osVersion = "14",
            deviceTimezone = "UTC", deviceLocale = "en-US",
            supportedAbis = emptyList(), deviceMetadata = emptyMap(),
        )
        val device = buildOpenDevice(ctx)

        assertEquals("Pixel", device["model"])
        assertEquals("Google", device["manufacturer"])
        assertEquals("14", device["osVersion"])
        assertEquals("UTC", device["timezone"])
        assertEquals("en-US", device["language"])

        for (key in listOf(
            "brand", "hardware", "name", "isPhysicalDevice", "screenWidth", "screenHeight",
            "screenResolution", "devicePixelRatio", "colorDepth", "supportedAbis", "metadata",
            "advertisingId", "androidId",
        )) {
            assertFalse(device.containsKey(key), "expected `$key` to be omitted when null/empty")
        }
    }

    @Test
    fun wrapperDeviceContextThreadsThroughAndWinsOverAutoCapture() {
        // Simulate the Android auto-capture base, then overlay a wrapper context.
        val autoCaptured = AttriaxContextSnapshot(
            packageName = "com.x", appVersion = "1.2.3", appBuildNumber = "45",
            deviceModel = "auto-model", deviceManufacturer = "auto-mfr", osVersion = "auto-os",
            deviceTimezone = "auto-tz", deviceLocale = "auto-lang",
            deviceBrand = "auto-brand", deviceHardware = "auto-hw",
            screenWidth = 100, screenHeight = 200,
            supportedAbis = listOf("arm64-v8a"),
        )
        val wrapper = AttriaxDeviceContext(
            model = "w-model", manufacturer = "w-mfr", osVersion = "17.1",
            timezone = "Europe/Berlin", language = "de-DE",
            // Overrides one optional; leaves hardware/screen* unset so auto-capture survives.
            brand = "w-brand",
            name = "w-name",
            isPhysicalDevice = false,
            devicePixelRatio = 3.0,
            colorDepth = 32,
            metadata = mapOf("integration" to "unity"),
            advertisingId = "w-ad", androidId = "w-aid",
        )

        val device = buildOpenDevice(autoCaptured.withDeviceContext(wrapper))

        // Required five — wrapper wins unconditionally.
        assertEquals("w-model", device["model"])
        assertEquals("w-mfr", device["manufacturer"])
        assertEquals("17.1", device["osVersion"])
        assertEquals("Europe/Berlin", device["timezone"])
        assertEquals("de-DE", device["language"])

        // Optional: wrapper value wins where supplied.
        assertEquals("w-brand", device["brand"])
        assertEquals("w-name", device["name"])
        assertEquals(false, device["isPhysicalDevice"])
        assertEquals(3.0, device["devicePixelRatio"])
        assertEquals(32, device["colorDepth"])
        assertEquals(mapOf("integration" to "unity"), device["metadata"])
        assertEquals("w-ad", device["advertisingId"])
        assertEquals("w-aid", device["androidId"])

        // Optional: auto-captured value preserved where wrapper left it null.
        assertEquals("auto-hw", device["hardware"])
        assertEquals(100, device["screenWidth"])
        assertEquals(200, device["screenHeight"])
        assertEquals(listOf("arm64-v8a"), device["supportedAbis"])
    }

    @Test
    fun withNullDeviceContextIsIdentity() {
        val base = AttriaxContextSnapshot(
            packageName = "com.x", appVersion = "1", appBuildNumber = "1",
            deviceModel = "m", deviceManufacturer = "mf", osVersion = "14",
            deviceTimezone = "UTC", deviceLocale = "en-US", deviceBrand = "b",
        )
        assertTrue(base === base.withDeviceContext(null))
    }
}
