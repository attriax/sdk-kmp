package com.attriax.sdk.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** real, stable User-Agent with the mandatory parenthetical suffix. */
class AttriaxUserAgentTest {

    @Test
    fun formatsRealSdkUserAgentWithSuffix() {
        val ua = AttriaxUserAgent.format(osVersion = "14", descriptor = "com.example.app", packageVersion = "0.5.0")
        assertEquals("attriax-android-sdk/0.5.0 (Android 14; com.example.app)", ua)
    }

    @Test
    fun includesTheLoadBearingParentheticalSuffix() {
        val ua = AttriaxUserAgent.format(osVersion = "13", descriptor = "Pixel 7")
        // The bare form without "(Android ...; ...)" still trips isbot — assert it's present.
        assertTrue(ua.contains("(Android 13; Pixel 7)"))
        assertTrue(ua.startsWith("attriax-android-sdk/"))
    }

    @Test
    fun isNotTheGeneratorDefaultThatTripsIsbot() {
        val ua = AttriaxUserAgent.format(osVersion = "12", descriptor = "com.x")
        assertTrue(!ua.startsWith("OpenAPI-Generator"))
    }

    @Test
    fun fallsBackToUnknownForBlankInputs() {
        val ua = AttriaxUserAgent.format(osVersion = "", descriptor = "", packageVersion = "0.5.0")
        assertEquals("attriax-android-sdk/0.5.0 (Android unknown; unknown)", ua)
    }
}
