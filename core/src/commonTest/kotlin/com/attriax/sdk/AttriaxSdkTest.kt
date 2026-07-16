package com.attriax.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class AttriaxSdkTest {

    @Test
    fun exposesParityVersion() {
        // Must match ATTRIAX_VERSION in gradle.properties (the published
        // `com.attriax:core` coordinate) — this constant is what a device reports on
        // the wire, so drift makes the published version unidentifiable in analytics.
        assertEquals("0.6.1", AttriaxVersion.PACKAGE_VERSION)
        assertEquals("v1", AttriaxVersion.API_VERSION)
    }
}
