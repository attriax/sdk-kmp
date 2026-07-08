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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Engine-level deep-link wiring (PARITY §6, rows DL2/DL3): resolve requests
 * traverse the real queue + dispatcher and emit resolved events; deferred links are
 * recovered from the app-open RESPONSE and fired once. With a synchronous flush
 * executor the resolve/flush/emit chain completes inline, and a late-added listener
 * is replayed the latest event, so no wall-clock polling is needed. Transport
 * bodies here are already envelope-unwrapped (the [HttpResponse.body] contract).
 */
class AttriaxDeepLinkEngineTest {

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

    /**
     * Returns the unwrapped `data` map per endpoint. The open response carries a
     * deferred deep link; the resolve response is a matched resolution.
     */
    private class DeepLinkTransport : HttpClient {
        val posts = mutableListOf<Pair<String, String>>()
        override fun post(path: String, body: String): HttpResponse {
            posts.add(path to body)
            return when (path) {
                "/api/sdk/v1/open" -> HttpResponse(
                    200,
                    """{"userId":"u1","isNewUser":true,"isFirstLaunch":true,"installState":"newInstall",""" +
                        """"deepLink":{"path":"deferred/promo","uri":"https://sub.attriax.com/deferred/promo"}}""",
                )
                "/api/sdk/v1/deep-links/resolve" -> HttpResponse(
                    200,
                    """{"requestVersion":"v1","matched":true,"status":"matched","isFirstLaunch":false,""" +
                        """"deepLink":{"path":"promo/summer","uri":"https://sub.attriax.com/promo/summer"}}""",
                )
                "/api/sdk/v1/dynamic-links" -> HttpResponse(
                    200,
                    """{"requestVersion":"v1","link":{"id":"L1","path":"sumabc","shortUrl":"https://acme.attriax.com/sumabc","name":"Summer"}}""",
                )
                else -> HttpResponse(200, "{}")
            }
        }
    }

    private class FixedSources(private val ssaid: String?) : DeviceIdSources {
        override fun androidSsaid(): String? = ssaid
        override fun advertisingId(): String? = null
    }

    private val context = AttriaxContextSnapshot(
        packageName = "com.x",
        appVersion = "1.0.0",
        appBuildNumber = "1",
        deviceModel = "Pixel",
        deviceManufacturer = "Google",
        osVersion = "14",
        deviceTimezone = "UTC",
        deviceLocale = "en-US",
    )

    private fun engine(store: MapStore, transport: DeepLinkTransport, firstLaunch: Boolean): Attriax {
        if (!firstLaunch) store.putString("attriax.first_launch_completed", "false")
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
            flushExecutor = AttriaxTestBackgroundExecutor(),
            consentExecutor = AttriaxTestBackgroundExecutor(),
        ).also { it.init() }
    }

    @Test
    fun resolveTraversesQueueAndEmitsResolvedEvent() {
        val transport = DeepLinkTransport()
        val store = MapStore()
        val sdk = engine(store, transport, firstLaunch = false)
        val received = ArrayList<AttriaxDeepLinkEvent>()
        sdk.deepLinks.addListener { received.add(it) }

        sdk.deepLinks.handleUri("https://sub.attriax.com/promo/summer", isInitialLink = false)
        // The synchronous flush resolves + emits inline. (The app-open response also
        // carries a deferred link that emits separately; select the foreground one.)

        val event = received.first { it.isForeground }
        assertTrue(event.found)
        assertEquals("https://sub.attriax.com/promo/summer", event.uri.toString())
        // The resolve request was actually POSTed to the resolve endpoint.
        assertTrue(transport.posts.any { it.first == "/api/sdk/v1/deep-links/resolve" })
        // Its body carries the normalized linkPath.
        val resolveBody = transport.posts.first { it.first == "/api/sdk/v1/deep-links/resolve" }.second
        val decoded = com.attriax.sdk.internal.json.Json.decodeObject(resolveBody)
        assertEquals("promo/summer", decoded["linkPath"])
        assertEquals("tok", decoded["projectToken"])
    }

    @Test
    fun deferredDeepLinkRecoveredFromAppOpenResponse() {
        val transport = DeepLinkTransport()
        val store = MapStore()
        val sdk = engine(store, transport, firstLaunch = true)
        val received = ArrayList<AttriaxDeepLinkEvent>()
        // The app-open fired + delivered during init(); its response carried the
        // deferred link, so the latest event is replayed to this late listener.
        sdk.deepLinks.addListener { received.add(it) }

        val deferred = received.first { it.isDeferred }
        assertEquals("https://sub.attriax.com/deferred/promo", deferred.uri.toString())
        assertEquals(AttriaxDeepLinkTrigger.DEFERRED, deferred.trigger)
        // Persisted handled flag prevents a second emit.
        assertEquals("true", store.getString("attriax.deferred_deep_link_handled"))
    }

    @Test
    fun createDynamicLinkReturnsShortUrlAndRecord() {
        val transport = DeepLinkTransport()
        val sdk = engine(MapStore(), transport, firstLaunch = false)

        val result = sdk.deepLinks.createDynamicLink(name = "Summer", destinationUrl = "https://example.com")

        assertEquals("https://acme.attriax.com/sumabc", result.shortUrl)
        assertEquals("L1", result.record.id)
        assertEquals("Summer", result.record.name)
        assertTrue(transport.posts.any { it.first == "/api/sdk/v1/dynamic-links" })
    }
}
