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
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Apple Search Ads (AdServices) token capture driven through the REAL
 * [Attriax] engine with fakes. Proves the FROZEN wire `{projectToken, token}` to
 * `/api/sdk/v1/asa/token`, the config-flag / null-token gating of the init auto-capture,
 * and that wrapper-supply works irrespective of the platform fetch seam.
 */
class AttriaxAsaTokenTest {

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
        transport: RecordingTransport = RecordingTransport(),
        config: AttriaxConfig = AttriaxConfig(projectToken = "tok"),
        asaToken: String? = null,
    ): Attriax {
        val store = MapStore()
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
            asaTokenFetchSeam = { asaToken },
        ).also { it.init() }
    }

    private fun asaPosts(transport: RecordingTransport): List<String> =
        transport.posts.filter { it.first == AttriaxEndpoints.ASA_TOKEN }.map { it.second }

    @Suppress("UNCHECKED_CAST")
    private fun decode(body: String): Map<String, Any?> = Json.decode(body) as Map<String, Any?>

    // -------- init auto-capture --------

    @Test
    fun autoCaptureSubmitsExactWireWhenSeamReturnsToken() {
        val transport = RecordingTransport()
        newEngine(transport = transport, asaToken = "aa-token")
        val posts = asaPosts(transport)
        assertEquals(1, posts.size)
        val body = decode(posts.single())
        // Exactly { projectToken, token } — the frozen contract.
        assertEquals(setOf("projectToken", "token"), body.keys)
        assertEquals("tok", body["projectToken"])
        assertEquals("aa-token", body["token"])
    }

    @Test
    fun autoCaptureDoesNotSubmitWhenSeamReturnsNull() {
        val transport = RecordingTransport()
        // The off-iOS default: fetch seam returns null → nothing sent.
        newEngine(transport = transport, asaToken = null)
        assertTrue(asaPosts(transport).isEmpty())
    }

    @Test
    fun autoCaptureDoesNotSubmitWhenDisabledByConfig() {
        val transport = RecordingTransport()
        newEngine(
            transport = transport,
            config = AttriaxConfig(projectToken = "tok", asaTokenCaptureEnabled = false),
            asaToken = "aa-token",
        )
        assertTrue(asaPosts(transport).isEmpty())
    }

    @Test
    fun autoCaptureDoesNotSubmitWhenBufferedForAttributionConsent() {
        val transport = RecordingTransport()
        // gdprEnabled + no consent decision → attribution dispatch buffered → no ASA send.
        newEngine(
            transport = transport,
            config = AttriaxConfig(projectToken = "tok", gdprEnabled = true, anonymousTracking = false),
            asaToken = "aa-token",
        )
        assertTrue(asaPosts(transport).isEmpty())
    }

    // -------- wrapper-supply --------

    @Test
    fun submitAsaTokenPostsExactWireEvenWhenSeamIsNull() {
        val transport = RecordingTransport()
        val engine = newEngine(transport = transport, asaToken = null)
        assertTrue(asaPosts(transport).isEmpty())

        engine.submitAsaToken("wrapper-token")
        val posts = asaPosts(transport)
        assertEquals(1, posts.size)
        val body = decode(posts.single())
        assertEquals(setOf("projectToken", "token"), body.keys)
        assertEquals("tok", body["projectToken"])
        assertEquals("wrapper-token", body["token"])
    }

    @Test
    fun submitAsaTokenIgnoresBlankToken() {
        val transport = RecordingTransport()
        val engine = newEngine(transport = transport, asaToken = null)
        engine.submitAsaToken("   ")
        assertTrue(asaPosts(transport).isEmpty())
    }

    @Test
    fun submitAsaTokenWorksEvenWhenAutoCaptureDisabled() {
        val transport = RecordingTransport()
        val engine = newEngine(
            transport = transport,
            config = AttriaxConfig(projectToken = "tok", asaTokenCaptureEnabled = false),
            asaToken = "aa-token",
        )
        assertTrue(asaPosts(transport).isEmpty())
        engine.submitAsaToken("wrapper-token")
        assertEquals("wrapper-token", decode(asaPosts(transport).single())["token"])
    }

    @Test
    fun autoCaptureRunsAtMostOnce() {
        val transport = RecordingTransport()
        val engine = newEngine(transport = transport, asaToken = "aa-token")
        // A second init() is a no-op (initialized latch); no second auto-capture.
        engine.init()
        assertEquals(1, asaPosts(transport).size)
    }

    // sanity: nothing about ASA disturbs the app-open pipeline
    @Test
    fun appOpenStillFiresAlongsideAsaCapture() {
        val transport = RecordingTransport()
        newEngine(transport = transport, asaToken = "aa-token")
        assertTrue(transport.posts.any { it.first == AttriaxEndpoints.OPEN })
    }
}
