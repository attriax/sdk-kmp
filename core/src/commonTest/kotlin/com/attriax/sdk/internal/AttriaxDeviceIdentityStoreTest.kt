package com.attriax.sdk.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Key naming + the `attriax.device_id` → `attriax.device_id_fallback` migration.
 *
 * The load-bearing case is [upgradeFromLegacyOnlyStoreKeepsTheSameIdOnFallbackPlatform]:
 * on a platform with no native id the fallback seed IS the reported identity, so losing
 * it on upgrade would silently turn every existing install into a new device.
 */
class AttriaxDeviceIdentityStoreTest {

    private class FakeStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
        val values = initial.toMutableMap()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
        override fun remove(key: String) { values.remove(key) }
    }

    private class FakeSources(val ssaid: String? = null) : DeviceIdSources {
        override fun androidSsaid(): String? = ssaid
        override fun advertisingId(): String? = null
    }

    /** No native id resolves → the fallback seed is the reported identity. */
    private fun fallbackOnlyResolver() =
        AttriaxDeviceIdentityResolver(FakeSources(), collectAdvertisingId = false)

    /** Android-with-SSAID → the seed is dormant; the SSAID is reported. */
    private fun ssaidResolver(ssaid: String) =
        AttriaxDeviceIdentityResolver(FakeSources(ssaid), collectAdvertisingId = false)

    // -------- migration matrix --------

    /** UPGRADE PATH — the regression that would silently destroy identities. */
    @Test
    fun upgradeFromLegacyOnlyStoreKeepsTheSameIdOnFallbackPlatform() {
        val store = FakeStore(mapOf(AttriaxDeviceIdentityStore.LEGACY_KEY_DEVICE_ID to "legacy-id-1"))
        val resolved = AttriaxDeviceIdentityStore(store, fallbackOnlyResolver()).loadOrCreate()

        assertEquals("legacy-id-1", resolved.value)
        assertTrue(resolved.isFallback)
        assertEquals(AttriaxDeviceIdSource.PERSISTENT_STORAGE, resolved.source)
        // ...and it is now carried forward under the honest key.
        assertEquals("legacy-id-1", store.values[AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK])
    }

    /** The legacy seed survives an upgrade even when it is dormant (Android-with-SSAID). */
    @Test
    fun upgradeCarriesLegacySeedForwardEvenWhenNativeIdIsReported() {
        val store = FakeStore(mapOf(AttriaxDeviceIdentityStore.LEGACY_KEY_DEVICE_ID to "legacy-id-1"))
        val resolved = AttriaxDeviceIdentityStore(store, ssaidResolver("ssaid-1")).loadOrCreate()

        assertEquals("ssaid-1", resolved.value)
        assertEquals("legacy-id-1", store.values[AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK])
    }

    @Test
    fun migrationRemovesTheLegacyKey() {
        val store = FakeStore(mapOf(AttriaxDeviceIdentityStore.LEGACY_KEY_DEVICE_ID to "legacy-id-1"))
        AttriaxDeviceIdentityStore(store, fallbackOnlyResolver()).loadOrCreate()

        assertNull(store.values[AttriaxDeviceIdentityStore.LEGACY_KEY_DEVICE_ID])
    }

    /** ALREADY MIGRATED — the new key wins and is never re-minted. */
    @Test
    fun alreadyMigratedStoreReusesTheNewKey() {
        val store = FakeStore(mapOf(AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK to "new-id-1"))
        val resolved = AttriaxDeviceIdentityStore(store, fallbackOnlyResolver()).loadOrCreate()

        assertEquals("new-id-1", resolved.value)
    }

    /** BOTH PRESENT (e.g. an interrupted migration) — the new key wins, legacy is ignored. */
    @Test
    fun bothKeysPresentPrefersTheNewKey() {
        val store = FakeStore(
            mapOf(
                AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK to "new-id-1",
                AttriaxDeviceIdentityStore.LEGACY_KEY_DEVICE_ID to "legacy-id-1",
            )
        )
        val resolved = AttriaxDeviceIdentityStore(store, fallbackOnlyResolver()).loadOrCreate()

        assertEquals("new-id-1", resolved.value)
    }

    /** FRESH INSTALL — mint once, then reuse across launches. */
    @Test
    fun freshInstallMintsAStableSeed() {
        val store = FakeStore()
        val subject = AttriaxDeviceIdentityStore(store, fallbackOnlyResolver())

        val first = subject.loadOrCreate().value
        val second = subject.loadOrCreate().value

        assertTrue(first.isNotEmpty())
        assertEquals(first, second)
        assertEquals(first, store.values[AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK])
        assertNull(store.values[AttriaxDeviceIdentityStore.LEGACY_KEY_DEVICE_ID])
    }

    // -------- the store is self-describing --------

    @Test
    fun persistsTheReportedIdentityNextToItsSource() {
        val store = FakeStore()
        AttriaxDeviceIdentityStore(store, ssaidResolver("ssaid-1")).loadOrCreate()

        assertEquals("ssaid-1", store.values[AttriaxDeviceIdentityStore.KEY_DEVICE_ID_RESOLVED])
        assertEquals(AttriaxDeviceIdSource.ANDROID_SSAID, store.values[AttriaxDeviceIdentityStore.KEY_DEVICE_ID_SOURCE])
        // The dormant seed is a DIFFERENT value and is now named as such.
        assertNotEquals("ssaid-1", store.values[AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK])
    }

    @Test
    fun resolvedIdentityIsRewrittenWhenTheNativeSourceChanges() {
        val store = FakeStore()
        AttriaxDeviceIdentityStore(store, ssaidResolver("ssaid-1")).loadOrCreate()
        AttriaxDeviceIdentityStore(store, ssaidResolver("ssaid-2")).loadOrCreate()

        assertEquals("ssaid-2", store.values[AttriaxDeviceIdentityStore.KEY_DEVICE_ID_RESOLVED])
    }

    // -------- clear --------

    @Test
    fun clearRemovesEveryKeyIncludingLegacy() {
        val store = FakeStore(mapOf(AttriaxDeviceIdentityStore.LEGACY_KEY_DEVICE_ID to "legacy-id-1"))
        val subject = AttriaxDeviceIdentityStore(store, ssaidResolver("ssaid-1"))
        subject.loadOrCreate()

        subject.clear()

        assertTrue(store.values.isEmpty(), "leftover keys: ${store.values.keys}")
    }

    /** A reset must not be undone by migration resurrecting the legacy seed. */
    @Test
    fun clearThenLoadMintsAFreshSeedRatherThanResurrectingLegacy() {
        val store = FakeStore(mapOf(AttriaxDeviceIdentityStore.LEGACY_KEY_DEVICE_ID to "legacy-id-1"))
        val subject = AttriaxDeviceIdentityStore(store, fallbackOnlyResolver())

        subject.clear()
        val resolved = subject.loadOrCreate()

        assertNotEquals("legacy-id-1", resolved.value)
    }
}
