package com.attriax.sdk.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PARITY rows D4 — deviceIdSource precedence + collectAdvertisingId gating. */
class AttriaxDeviceIdentityResolverTest {

    private class FakeSources(val ssaid: String?, val gaid: String?) : DeviceIdSources {
        override fun androidSsaid(): String? = ssaid
        override fun advertisingId(): String? = gaid
    }

    @Test
    fun prefersSsaidWhenPresent() {
        val resolver = AttriaxDeviceIdentityResolver(FakeSources("ssaid-1", "gaid-1"), collectAdvertisingId = true)
        val resolved = resolver.resolve("fallback")
        assertEquals("ssaid-1", resolved.value)
        assertEquals(AttriaxDeviceIdSource.ANDROID_SSAID, resolved.source)
        assertFalse(resolved.isFallback)
    }

    @Test
    fun fallsBackToGaidWhenNoSsaid() {
        val resolver = AttriaxDeviceIdentityResolver(FakeSources(null, "gaid-1"), collectAdvertisingId = true)
        val resolved = resolver.resolve("fallback")
        assertEquals("gaid-1", resolved.value)
        assertEquals(AttriaxDeviceIdSource.ANDROID_GAID, resolved.source)
    }

    @Test
    fun treatsEmptyStringAsAbsent() {
        val resolver = AttriaxDeviceIdentityResolver(FakeSources("", ""), collectAdvertisingId = true)
        val resolved = resolver.resolve("fallback")
        assertEquals("fallback", resolved.value)
        assertEquals(AttriaxDeviceIdSource.PERSISTENT_STORAGE, resolved.source)
        assertTrue(resolved.isFallback)
    }

    @Test
    fun skipsGaidWhenCollectionDisabled() {
        val resolver = AttriaxDeviceIdentityResolver(FakeSources(null, "gaid-1"), collectAdvertisingId = false)
        val resolved = resolver.resolve("fallback")
        assertEquals("fallback", resolved.value)
        assertEquals(AttriaxDeviceIdSource.PERSISTENT_STORAGE, resolved.source)
    }

    @Test
    fun ssaidStillWinsEvenWhenAdvertisingDisabled() {
        val resolver = AttriaxDeviceIdentityResolver(FakeSources("ssaid-1", "gaid-1"), collectAdvertisingId = false)
        val resolved = resolver.resolve("fallback")
        assertEquals("ssaid-1", resolved.value)
        assertEquals(AttriaxDeviceIdSource.ANDROID_SSAID, resolved.source)
    }
}
