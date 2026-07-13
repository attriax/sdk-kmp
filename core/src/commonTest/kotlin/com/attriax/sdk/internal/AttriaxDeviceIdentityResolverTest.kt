package com.attriax.sdk.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** deviceIdSource precedence + collectAdvertisingId gating. */
class AttriaxDeviceIdentityResolverTest {

    private class FakeSources(val ssaid: String?, val gaid: String?) : DeviceIdSources {
        override fun androidSsaid(): String? = ssaid
        override fun advertisingId(): String? = gaid
    }

    /** iOS-shaped sources: an IDFV primary + an (ATT-gated) IDFA advertising id. */
    private class FakeAppleSources(val idfv: String?, val idfa: String?) : DeviceIdSources {
        override fun androidSsaid(): String? = null
        override fun advertisingId(): String? = idfa
        override fun iosIdfv(): String? = idfv
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

    // -------- iOS branch (iOS: IDFV → IDFA → persistent) --------

    @Test
    fun prefersIdfvWhenPresent() {
        val resolver = AttriaxDeviceIdentityResolver(
            FakeAppleSources("idfv-1", "idfa-1"),
            collectAdvertisingId = true,
            advertisingIdSource = AttriaxDeviceIdSource.IOS_IDFA,
        )
        val resolved = resolver.resolve("fallback")
        assertEquals("idfv-1", resolved.value)
        assertEquals(AttriaxDeviceIdSource.IOS_IDFV, resolved.source)
        assertFalse(resolved.isFallback)
    }

    @Test
    fun fallsBackToIdfaWhenNoIdfv() {
        val resolver = AttriaxDeviceIdentityResolver(
            FakeAppleSources(null, "idfa-1"),
            collectAdvertisingId = true,
            advertisingIdSource = AttriaxDeviceIdSource.IOS_IDFA,
        )
        val resolved = resolver.resolve("fallback")
        assertEquals("idfa-1", resolved.value)
        assertEquals(AttriaxDeviceIdSource.IOS_IDFA, resolved.source)
    }

    @Test
    fun idfvStillWinsEvenWhenAdvertisingDisabled() {
        val resolver = AttriaxDeviceIdentityResolver(
            FakeAppleSources("idfv-1", "idfa-1"),
            collectAdvertisingId = false,
            advertisingIdSource = AttriaxDeviceIdSource.IOS_IDFA,
        )
        val resolved = resolver.resolve("fallback")
        assertEquals("idfv-1", resolved.value)
        assertEquals(AttriaxDeviceIdSource.IOS_IDFV, resolved.source)
    }

    @Test
    fun skipsIdfaWhenCollectionDisabled() {
        val resolver = AttriaxDeviceIdentityResolver(
            FakeAppleSources(null, "idfa-1"),
            collectAdvertisingId = false,
            advertisingIdSource = AttriaxDeviceIdSource.IOS_IDFA,
        )
        val resolved = resolver.resolve("fallback")
        assertEquals("fallback", resolved.value)
        assertEquals(AttriaxDeviceIdSource.PERSISTENT_STORAGE, resolved.source)
        assertTrue(resolved.isFallback)
    }
}
