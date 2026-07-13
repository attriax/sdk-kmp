package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.ConnectivityMonitor
import com.attriax.sdk.internal.DeviceIdSources
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerCoordinator
import com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerDetails
import com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerProvider
import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * (app-open install-referrer enrichment) driven through the REAL
 * [Attriax] engine with a fake Play provider. Proves the four wire fields reach
 * the `/open` body on first launch, that the raw referrer is persisted + re-
 * attached (without timestamps) on the next launch, and that the Unavailable
 * provider leaves the open free of referrer fields. The (synchronous fake) flush
 * executor resolves + flushes the open inline during init, so the wire body is the
 * source of truth after `init()` returns.
 */
class AttriaxInstallReferrerEngineTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    private class NoopConnectivity : ConnectivityMonitor {
        override fun isConnected(): Boolean = false
        override fun register(listener: ConnectivityMonitor.Listener) {}
        override fun unregister(listener: ConnectivityMonitor.Listener) {}
    }

    private class RecordingTransport : HttpClient {
        val posts = mutableListOf<Pair<String, String>>()
        override fun post(path: String, body: String): HttpResponse {
            posts.add(path to body)
            return HttpResponse(200, "{}")
        }
    }

    private class FixedSources(private val ssaid: String?) : DeviceIdSources {
        override fun androidSsaid(): String? = ssaid
        override fun advertisingId(): String? = null
    }

    private class FakeProvider(private val details: AttriaxInstallReferrerDetails?) :
        AttriaxInstallReferrerProvider {
        var calls: Int = 0
        override fun fetch(): AttriaxInstallReferrerDetails? {
            calls++
            return details
        }
    }

    private val context = AttriaxContextSnapshot(
        packageName = "com.example.app",
        appVersion = "1.0.0",
        appBuildNumber = "1",
        deviceModel = "Pixel 5",
        deviceManufacturer = "Google",
        osVersion = "14",
        deviceTimezone = "UTC",
        deviceLocale = "en-US",
    )

    private fun newEngine(
        store: MapStore,
        transport: RecordingTransport,
        provider: AttriaxInstallReferrerProvider,
    ): Attriax {
        val resolver = AttriaxDeviceIdentityResolver(FixedSources("SSAID-123"), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = AttriaxConfig(projectToken = "tok"),
            store = store,
            transport = transport,
            connectivity = NoopConnectivity(),
            context = context,
            deviceIdentityStore = identityStore,
            clock = AttriaxClock { 1_000L },
            installReferrerProvider = provider,
            flushExecutor = AttriaxTestBackgroundExecutor(),
            consentExecutor = AttriaxTestBackgroundExecutor(),
        ).also { it.init() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun openBody(transport: RecordingTransport): Map<String, Any?>? {
        val open = transport.posts.firstOrNull { it.first == AttriaxEndpoints.OPEN } ?: return null
        return Json.decode(open.second) as Map<String, Any?>
    }

    private val fullDetails = AttriaxInstallReferrerDetails(
        rawReferrer = "utm_source=google-play&utm_medium=cpc&utm_campaign=spring",
        installBeginTimestampSeconds = 1_700_000_000L,
        referrerClickTimestampSeconds = 1_699_999_000L,
        googlePlayInstantParam = false,
    )

    @Test
    fun firstLaunchOpenCarriesAllReferrerFields() {
        val store = MapStore()
        val transport = RecordingTransport()
        val provider = FakeProvider(fullDetails)
        newEngine(store, transport, provider)

        val body = openBody(transport)!!
        assertEquals(fullDetails.rawReferrer, body["installReferrer"])
        // JSON round-trips integers as Long in this codec.
        assertEquals(1_700_000_000L, (body["installBeginTimestampSeconds"] as Number).toLong())
        assertEquals(1_699_999_000L, (body["referrerClickTimestampSeconds"] as Number).toLong())
        assertEquals(false, body["googlePlayInstantParam"])
        assertEquals(1, provider.calls)
        // Raw string persisted for subsequent launches.
        assertEquals(
            fullDetails.rawReferrer,
            store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER],
        )
    }

    @Test
    fun secondLaunchReattachesCachedRawWithoutTimestampsOrFetch() {
        val store = MapStore()
        // Pre-seed the cache as if a prior launch had resolved the referrer.
        store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER] =
            "utm_source=google-play&utm_medium=organic"
        val transport = RecordingTransport()
        val provider = FakeProvider(fullDetails)
        newEngine(store, transport, provider)

        val body = openBody(transport)!!
        assertEquals("utm_source=google-play&utm_medium=organic", body["installReferrer"])
        assertFalse(body.containsKey("installBeginTimestampSeconds"))
        assertFalse(body.containsKey("referrerClickTimestampSeconds"))
        assertFalse(body.containsKey("googlePlayInstantParam"))
        // Cache hit → provider never contacted.
        assertEquals(0, provider.calls)
    }

    @Test
    fun unavailableProviderOpenHasNoReferrerFields() {
        val store = MapStore()
        val transport = RecordingTransport()
        newEngine(store, transport, AttriaxInstallReferrerProvider.Unavailable)

        val body = openBody(transport)!!
        assertFalse(body.containsKey("installReferrer"))
        assertFalse(body.containsKey("installBeginTimestampSeconds"))
        assertNull(store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER])
    }
}
