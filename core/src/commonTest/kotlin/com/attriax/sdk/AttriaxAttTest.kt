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
 * Apple ATT (App Tracking Transparency) surface + push-token wire
 * driven through the REAL [Attriax] engine with fakes. Proves:
 *  - a wrapper-supplied / seam-resolved ATT status lands TOP-LEVEL as `attStatus`
 *    on the app-open (and is OMITTED when UNKNOWN),
 *  - `consent.att.status` returns the wrapper-supplied value, else the seam default,
 *  - `requestTrackingAuthorizationOnInit` gates the init-time request seam,
 *  - `registerApplePushToken` produces the `provider = "apns"` uninstall-token wire.
 */
class AttriaxAttTest {

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
        attStatusProvider: () -> AttriaxAttStatus = { AttriaxAttStatus.UNKNOWN },
        requestAttAuthorizationSeam: (Long?) -> AttriaxAttStatus = { AttriaxAttStatus.UNKNOWN },
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
            attStatusProvider = attStatusProvider,
            requestAttAuthorizationSeam = requestAttAuthorizationSeam,
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

    // -------- attStatus on the open (TOP-LEVEL, omit-when-UNKNOWN) --------

    @Test
    fun openCarriesWrapperSuppliedAttStatus() {
        val transport = RecordingTransport()
        newEngine(
            transport = transport,
            config = AttriaxConfig(projectToken = "tok", attStatus = AttriaxAttStatus.AUTHORIZED),
        )
        // TOP-LEVEL, mirroring attestation — NOT nested under device.
        assertEquals("authorized", openBody(transport)["attStatus"])
    }

    @Test
    fun openCarriesEachRealStatusWireValue() {
        for ((status, wire) in listOf(
            AttriaxAttStatus.AUTHORIZED to "authorized",
            AttriaxAttStatus.DENIED to "denied",
            AttriaxAttStatus.RESTRICTED to "restricted",
            AttriaxAttStatus.NOT_DETERMINED to "notDetermined",
        )) {
            val transport = RecordingTransport()
            newEngine(
                transport = transport,
                config = AttriaxConfig(projectToken = "tok", attStatus = status),
            )
            assertEquals(wire, openBody(transport)["attStatus"], "wire mismatch for $status")
        }
    }

    @Test
    fun openOmitsAttStatusWhenUnknown() {
        val transport = RecordingTransport()
        // No wrapper status + seam UNKNOWN (the off-iOS default) → omitted.
        newEngine(transport = transport, attStatusProvider = { AttriaxAttStatus.UNKNOWN })
        assertFalse(openBody(transport).containsKey("attStatus"))
    }

    @Test
    fun openCarriesSeamResolvedStatusWhenNoWrapperStatus() {
        val transport = RecordingTransport()
        // No wrapper status → the engine falls back to the (fake iOS-like) seam.
        newEngine(transport = transport, attStatusProvider = { AttriaxAttStatus.RESTRICTED })
        assertEquals("restricted", openBody(transport)["attStatus"])
    }

    // -------- consent.att.status getter --------

    @Test
    fun attStatusGetterReturnsWrapperSuppliedValue() {
        val engine = newEngine(
            config = AttriaxConfig(projectToken = "tok", attStatus = AttriaxAttStatus.DENIED),
        )
        assertEquals(AttriaxAttStatus.DENIED, engine.consent.att.status)
    }

    @Test
    fun attStatusGetterFallsBackToSeamDefaultUnknown() {
        val engine = newEngine(attStatusProvider = { AttriaxAttStatus.UNKNOWN })
        assertEquals(AttriaxAttStatus.UNKNOWN, engine.consent.att.status)
    }

    @Test
    fun setStatusUpdatesStatusGetter() {
        val engine = newEngine(attStatusProvider = { AttriaxAttStatus.UNKNOWN })
        assertEquals(AttriaxAttStatus.UNKNOWN, engine.consent.att.status)
        engine.consent.att.setStatus(AttriaxAttStatus.AUTHORIZED)
        assertEquals(AttriaxAttStatus.AUTHORIZED, engine.consent.att.status)
    }

    // -------- requestTrackingAuthorizationOnInit gating --------

    @Test
    fun requestTrackingAuthorizationOnInitInvokesSeamAndLatches() {
        val transport = RecordingTransport()
        var seamCalls = 0
        var seenTimeout: Long? = -1
        newEngine(
            transport = transport,
            config = AttriaxConfig(
                projectToken = "tok",
                requestTrackingAuthorizationOnInit = true,
                trackingAuthorizationStatusTimeoutMs = 12_345L,
            ),
            requestAttAuthorizationSeam = { timeout ->
                seamCalls++
                seenTimeout = timeout
                AttriaxAttStatus.DENIED
            },
        )
        assertEquals(1, seamCalls)
        assertEquals(12_345L, seenTimeout)
        // The resolved (non-UNKNOWN) result is latched and rides the open.
        assertEquals("denied", openBody(transport)["attStatus"])
    }

    @Test
    fun requestTrackingAuthorizationNotInvokedWhenDisabled() {
        val transport = RecordingTransport()
        var seamCalls = 0
        newEngine(
            transport = transport,
            config = AttriaxConfig(projectToken = "tok", requestTrackingAuthorizationOnInit = false),
            requestAttAuthorizationSeam = { seamCalls++; AttriaxAttStatus.DENIED },
        )
        assertEquals(0, seamCalls)
        assertFalse(openBody(transport).containsKey("attStatus"))
    }

    @Test
    fun requestAuthorizationDoesNotLatchUnknown() {
        val engine = newEngine(
            config = AttriaxConfig(projectToken = "tok", attStatus = AttriaxAttStatus.AUTHORIZED),
            requestAttAuthorizationSeam = { AttriaxAttStatus.UNKNOWN },
        )
        // A no-op (off-iOS) UNKNOWN result must NOT clobber a real wrapper status.
        assertEquals(AttriaxAttStatus.UNKNOWN, engine.consent.att.requestAuthorization())
        assertEquals(AttriaxAttStatus.AUTHORIZED, engine.consent.att.status)
    }

    // -------- registerApplePushToken wire --------

    @Test
    fun registerApplePushTokenWiresApnsProvider() {
        val store = MapStore()
        val engine = newEngine(store = store)
        engine.tracking.registerApplePushToken("apns-device-token")
        val body = lastQueuedBody(store)
        assertEquals("tok", body["projectToken"])
        assertEquals("apns", body["provider"])
        assertEquals("apns-device-token", body["token"])
    }

    @Test
    fun registerApplePushTokenNullTokenDeRegisters() {
        val store = MapStore()
        val engine = newEngine(store = store)
        engine.tracking.registerApplePushToken(null)
        val body = lastQueuedBody(store)
        assertEquals("apns", body["provider"])
        assertNull(body["token"])
    }
}
