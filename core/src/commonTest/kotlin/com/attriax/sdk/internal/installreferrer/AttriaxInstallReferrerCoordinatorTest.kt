package com.attriax.sdk.internal.installreferrer

import com.attriax.sdk.internal.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Install-referrer capture policy, unit-tested against fakes — no
 * Android / Play client. Proves cache-first re-attachment, fetch-once-with-one-
 * retry, non-persistence of empty results, and full inertness when disabled or
 * when the provider is [AttriaxInstallReferrerProvider.Unavailable].
 */
class AttriaxInstallReferrerCoordinatorTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    /** Provider returning a scripted sequence of results; counts its invocations. */
    private class ScriptedProvider(
        private val results: MutableList<AttriaxInstallReferrerDetails?>,
    ) : AttriaxInstallReferrerProvider {
        var calls: Int = 0
        override fun fetch(): AttriaxInstallReferrerDetails? {
            calls++
            return if (results.isEmpty()) null else results.removeAt(0)
        }
    }

    private fun coordinator(
        provider: AttriaxInstallReferrerProvider,
        store: MapStore,
        enabled: Boolean = true,
    ) = AttriaxInstallReferrerCoordinator(
        provider = provider,
        store = store,
        enabled = enabled,
        // No real pause in tests.
        sleeper = {},
    )

    private val fullDetails = AttriaxInstallReferrerDetails(
        rawReferrer = "utm_source=google-play&utm_medium=organic",
        installBeginTimestampSeconds = 1_700_000_000L,
        referrerClickTimestampSeconds = 1_699_999_000L,
        googlePlayInstantParam = false,
    )

    // -------- needsFetch gating --------

    @Test
    fun needsFetchTrueWhenEnabledProviderPresentAndNoCache() {
        val coord = coordinator(ScriptedProvider(mutableListOf(fullDetails)), MapStore())
        assertTrue(coord.needsFetch())
    }

    @Test
    fun needsFetchFalseWhenDisabled() {
        val coord = coordinator(ScriptedProvider(mutableListOf(fullDetails)), MapStore(), enabled = false)
        assertFalse(coord.needsFetch())
    }

    @Test
    fun needsFetchFalseWhenProviderUnavailable() {
        val coord = coordinator(AttriaxInstallReferrerProvider.Unavailable, MapStore())
        assertFalse(coord.needsFetch())
    }

    @Test
    fun needsFetchFalseWhenAlreadyCached() {
        val store = MapStore()
        store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER] = "utm_source=x"
        val coord = coordinator(ScriptedProvider(mutableListOf(fullDetails)), store)
        assertFalse(coord.needsFetch())
    }

    // -------- fetchAndPersist: success --------

    @Test
    fun fetchSuccessPersistsRawAndReturnsTimestamps() {
        val store = MapStore()
        val provider = ScriptedProvider(mutableListOf(fullDetails))
        val result = coordinator(provider, store).fetchAndPersist()!!

        assertEquals(fullDetails.rawReferrer, result.rawReferrer)
        assertEquals(1_700_000_000L, result.installBeginTimestampSeconds)
        assertEquals(1_699_999_000L, result.referrerClickTimestampSeconds)
        assertEquals(false, result.googlePlayInstantParam)
        assertEquals(1, provider.calls)
        // Only the raw string is cached (timestamps are one-shot).
        assertEquals(
            fullDetails.rawReferrer,
            store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER],
        )
    }

    @Test
    fun fetchEmptyThenRetrySucceeds() {
        val store = MapStore()
        // First attempt empty (null), retry yields the referrer.
        val provider = ScriptedProvider(mutableListOf(null, fullDetails))
        val result = coordinator(provider, store).fetchAndPersist()!!

        assertEquals(fullDetails.rawReferrer, result.rawReferrer)
        assertEquals(2, provider.calls)
    }

    // -------- fetchAndPersist: failure paths never persist --------

    @Test
    fun bothAttemptsEmptyReturnsNullPersistsNothing() {
        val store = MapStore()
        val provider = ScriptedProvider(mutableListOf(null, null))
        val result = coordinator(provider, store).fetchAndPersist()

        assertNull(result)
        assertEquals(2, provider.calls)
        assertNull(store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER])
        // Not latched — a later launch can retry.
        assertTrue(coordinator(provider, store).needsFetch())
    }

    @Test
    fun blankReferrerIsTreatedAsEmpty() {
        val store = MapStore()
        val provider = ScriptedProvider(
            mutableListOf(
                AttriaxInstallReferrerDetails(rawReferrer = ""),
                AttriaxInstallReferrerDetails(rawReferrer = ""),
            ),
        )
        assertNull(coordinator(provider, store).fetchAndPersist())
        assertNull(store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER])
    }

    @Test
    fun providerThrowingDegradesToNull() {
        val store = MapStore()
        val provider = object : AttriaxInstallReferrerProvider {
            override fun fetch(): AttriaxInstallReferrerDetails? = throw RuntimeException("boom")
        }
        assertNull(coordinator(provider, store).fetchAndPersist())
        assertNull(store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER])
    }

    // -------- cachedDetails: re-attach without fetching --------

    @Test
    fun cachedDetailsReturnsRawOnlyNoFetch() {
        val store = MapStore()
        store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER] = "utm_source=cached"
        val provider = ScriptedProvider(mutableListOf(fullDetails))
        val cached = coordinator(provider, store).cachedDetails()!!

        assertEquals("utm_source=cached", cached.rawReferrer)
        assertNull(cached.installBeginTimestampSeconds)
        assertNull(cached.referrerClickTimestampSeconds)
        assertNull(cached.googlePlayInstantParam)
        assertEquals(0, provider.calls)
    }

    @Test
    fun cachedDetailsNullWhenNothingCached() {
        assertNull(coordinator(AttriaxInstallReferrerProvider.Unavailable, MapStore()).cachedDetails())
    }

    @Test
    fun cachedDetailsNullWhenDisabledEvenIfStored() {
        val store = MapStore()
        store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER] = "utm_source=x"
        assertNull(coordinator(AttriaxInstallReferrerProvider.Unavailable, store, enabled = false).cachedDetails())
    }

    @Test
    fun fetchAndPersistFallsBackToCacheWhenNotNeeded() {
        val store = MapStore()
        store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER] = "utm_source=cached"
        val provider = ScriptedProvider(mutableListOf(fullDetails))
        val result = coordinator(provider, store).fetchAndPersist()!!

        // needsFetch() is false (cache hit) → no provider call, cached raw returned.
        assertEquals("utm_source=cached", result.rawReferrer)
        assertEquals(0, provider.calls)
    }
}
