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
import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.queue.AttriaxQueueManager
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CCPA `doNotSell` / `usPrivacy` surface (the `consent.ccpa`
 * sub-surface) driven through the REAL [Attriax] engine with fakes. Proves:
 *  - a config-seeded / runtime-set CCPA election lands TOP-LEVEL as `doNotSell` /
 *    `usPrivacy` on the app-open AND identify (and is OMITTED when unset),
 *  - an explicit `false` doNotSell is EMITTED (it may clear a prior server latch),
 *  - the runtime setter overrides the config seed and is reflected on the next build,
 *  - `consent.ccpa.doNotSell` / `usPrivacy` return the current value.
 */
class AttriaxCcpaTest {

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

    /** Records posts so the app-open wire (the source of truth) is inspectable. */
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

    private fun newEngine(
        store: MapStore = MapStore(),
        transport: RecordingTransport = RecordingTransport(),
        config: AttriaxConfig = AttriaxConfig(projectToken = "tok"),
    ): Attriax {
        store.putString("attriax.first_launch_completed", "false")
        val resolver = AttriaxDeviceIdentityResolver(FixedSources("SSAID-123"), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = config,
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

    @Suppress("UNCHECKED_CAST")
    private fun openBody(transport: RecordingTransport): Map<String, Any?> {
        val open = transport.posts.first { it.first == AttriaxEndpoints.OPEN }
        return Json.decode(open.second) as Map<String, Any?>
    }

    /** The single non-open queued body (init enqueues one app-open first). */
    private fun lastQueuedBody(store: MapStore): Map<String, Any?> {
        val queue = AttriaxQueueManager(store, 500).readAll()
        val nonOpen = queue.map { it.request }.filter { !it.isAppOpen }
        assertTrue(nonOpen.isNotEmpty(), "expected a queued non-open request")
        return nonOpen.last().body
    }

    // -------- config carries the CCPA fields --------

    @Test
    fun configCarriesCcpaFields() {
        val config = AttriaxConfig(projectToken = "tok", doNotSell = true, usPrivacy = "1YYN")
        assertEquals(true, config.doNotSell)
        assertEquals("1YYN", config.usPrivacy)
    }

    @Test
    fun configDefaultsCcpaFieldsToNull() {
        val config = AttriaxConfig(projectToken = "tok")
        assertNull(config.doNotSell)
        assertNull(config.usPrivacy)
    }

    // -------- CCPA on the open (TOP-LEVEL, omit-when-unset) --------

    @Test
    fun openCarriesConfigSeededCcpaFields() {
        val transport = RecordingTransport()
        newEngine(
            transport = transport,
            config = AttriaxConfig(projectToken = "tok", doNotSell = true, usPrivacy = "1YYN"),
        )
        val body = openBody(transport)
        // TOP-LEVEL, mirroring attStatus — NOT nested under device.
        assertEquals(true, body["doNotSell"])
        assertEquals("1YYN", body["usPrivacy"])
    }

    @Test
    fun openOmitsCcpaFieldsWhenUnset() {
        val transport = RecordingTransport()
        // Default SDK (no CCPA config) → nothing emitted (byte-identical open to today).
        newEngine(transport = transport)
        val body = openBody(transport)
        assertFalse(body.containsKey("doNotSell"))
        assertFalse(body.containsKey("usPrivacy"))
    }

    @Test
    fun openEmitsExplicitFalseDoNotSell() {
        val transport = RecordingTransport()
        newEngine(transport = transport, config = AttriaxConfig(projectToken = "tok", doNotSell = false))
        val body = openBody(transport)
        // A deliberate false must ride the open (it may clear a prior server latch).
        assertTrue(body.containsKey("doNotSell"))
        assertEquals(false, body["doNotSell"])
    }

    // -------- consent.ccpa getters + runtime setter override --------

    @Test
    fun ccpaGettersReturnConfigSeed() {
        val engine = newEngine(
            config = AttriaxConfig(projectToken = "tok", doNotSell = true, usPrivacy = "1YNN"),
        )
        assertEquals(true, engine.consent.ccpa.doNotSell)
        assertEquals("1YNN", engine.consent.ccpa.usPrivacy)
    }

    @Test
    fun runtimeSetterOverridesConfigSeed() {
        val engine = newEngine(config = AttriaxConfig(projectToken = "tok", doNotSell = false, usPrivacy = "1---"))
        engine.consent.ccpa.setDoNotSell(true)
        engine.consent.ccpa.setUsPrivacy("1YYN")
        assertEquals(true, engine.consent.ccpa.doNotSell)
        assertEquals("1YYN", engine.consent.ccpa.usPrivacy)
    }

    @Test
    fun runtimeSetterReflectedOnNextIdentifyBuild() {
        val store = MapStore()
        // Config seeds false; the runtime setter flips it before the identify build.
        val engine = newEngine(store = store, config = AttriaxConfig(projectToken = "tok", doNotSell = false))
        engine.consent.ccpa.set(doNotSell = true, usPrivacy = "1YYN")
        engine.tracking.setUser("user-1")
        val body = lastQueuedBody(store)
        // identify carries the CURRENT (runtime-overridden) CCPA values TOP-LEVEL.
        assertEquals(true, body["doNotSell"])
        assertEquals("1YYN", body["usPrivacy"])
        assertEquals("user-1", body["externalUserId"])
    }

    @Test
    fun identifyOmitsCcpaFieldsWhenUnset() {
        val store = MapStore()
        val engine = newEngine(store = store)
        engine.tracking.setUser("user-1")
        val body = lastQueuedBody(store)
        assertFalse(body.containsKey("doNotSell"))
        assertFalse(body.containsKey("usPrivacy"))
    }
}
