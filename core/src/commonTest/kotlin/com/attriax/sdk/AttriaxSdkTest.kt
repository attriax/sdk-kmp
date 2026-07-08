package com.attriax.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class AttriaxSdkTest {

    @Test
    fun exposesParityVersion() {
        // Mirrors the Flutter reference package version (PARITY row I3).
        assertEquals("0.5.0", AttriaxVersion.PACKAGE_VERSION)
        assertEquals("v1", AttriaxVersion.API_VERSION)
    }
}
