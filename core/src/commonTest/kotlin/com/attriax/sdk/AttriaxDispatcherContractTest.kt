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
import com.attriax.sdk.internal.consent.AttriaxConsentStateWire
import com.attriax.sdk.internal.contract.AttriaxDispatchContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives the canonical [AttriaxDispatcher.execute] command table DIRECTLY (the same
 * table the C-ABI `route()`, and next the Android/Apple wrappers, forward to). It is
 * the platform-agnostic counterpart to the desktop-native
 * `AttriaxCApiDispatchContractTest`: because it lives in `commonTest`, it runs on the
 * JVM AND the native (mingw) targets, proving `execute` — not just the C-ABI adapter —
 * covers the whole [AttriaxDispatchContract.METHODS] surface and returns canonical
 * result shapes.
 */
class AttriaxDispatcherContractTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    private class FixedConnectivity(private val connected: Boolean) : ConnectivityMonitor {
        override fun isConnected(): Boolean = connected
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

    private class FixedSources : DeviceIdSources {
        override fun androidSsaid(): String? = "SSAID-123"
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

    /**
     * A network-free engine with fakes. The default EMPTY project token + un-initialized
     * engine mirrors the C-ABI contract test (state-mutating methods short-circuit on
     * `requireInitialized()`); the effects test opts into a real token + connectivity so
     * a flush actually reaches the recording transport.
     */
    private fun newEngine(
        transport: RecordingTransport = RecordingTransport(),
        projectToken: String = "",
        connected: Boolean = false,
        initialize: Boolean = false,
    ): Attriax {
        val store = MapStore()
        store.putString("attriax.first_launch_completed", "false")
        val resolver = AttriaxDeviceIdentityResolver(FixedSources(), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = AttriaxConfig(projectToken = projectToken),
            store = store,
            transport = transport,
            connectivity = FixedConnectivity(connected),
            context = context,
            deviceIdentityStore = identityStore,
            clock = AttriaxClock { 1_000L },
            flushExecutor = AttriaxTestBackgroundExecutor(),
            consentExecutor = AttriaxTestBackgroundExecutor(),
        ).also { if (initialize) it.init() }
    }

    private fun exec(engine: Attriax, method: String, params: Map<String, Any?> = emptyMap()) =
        AttriaxDispatcher.execute(engine, method, params)

    // -------- contract coverage (the execute-side guard) --------

    @Test
    fun executeCoversEveryContractMethod() {
        val engine = newEngine()
        try {
            // `init` / `dispose` are driven last so the engine stays uninitialized while
            // the state-mutating methods run; `getSessionReferrer` blocks on the
            // initial-deep-link probe by design, so it is verified structurally only.
            val drivenLast = listOf("init", "dispose")
            val notLiveDriven = "getSessionReferrer"

            assertTrue(notLiveDriven in AttriaxDispatchContract.METHODS)

            for (method in AttriaxDispatchContract.METHODS) {
                if (method in drivenLast || method == notLiveDriven) continue
                assertRouted(engine, method)
            }
            for (method in drivenLast) assertRouted(engine, method)

            // Sanity: a bogus method IS reported Unimplemented (proves the guard is live).
            assertTrue(
                exec(engine, "definitelyNotARealMethod") is AttriaxDispatchResult.Unimplemented,
                "expected Unimplemented for a bogus method",
            )
        } finally {
            AttriaxDispatcher.execute(engine, "dispose", emptyMap())
        }
    }

    private fun assertRouted(engine: Attriax, method: String) {
        // A wired case may still throw (e.g. `requireInitialized()` before `init`); that
        // is fine — it proves the call REACHED the engine. Only [Unimplemented] (the
        // `else` branch, which never throws) means the method is unwired.
        val result = try {
            exec(engine, method)
        } catch (e: Throwable) {
            return
        }
        if (result is AttriaxDispatchResult.Unimplemented) {
            fail(
                "AttriaxDispatcher.execute has no case for contract method '$method'. " +
                    "Either wire it into the execute() when, or remove it from " +
                    "AttriaxDispatchContract.METHODS.",
            )
        }
    }

    // -------- canonical result shapes + engine effects --------

    @Test
    fun executeProducesCanonicalResultShapesAndEffects() {
        val transport = RecordingTransport()
        val engine = newEngine(
            transport = transport,
            projectToken = "tok",
            connected = true,
            initialize = true,
        )

        // Primitive getter → Ok(String).
        val deviceId = exec(engine, "getDeviceId")
        assertTrue(deviceId is AttriaxDispatchResult.Ok)
        assertEquals(engine.deviceId, deviceId.value)

        // Boolean setter/getter round-trip.
        exec(engine, "setEnabled", mapOf("enabled" to false))
        assertEquals(AttriaxDispatchResult.Ok(false), exec(engine, "getEnabled"))
        exec(engine, "setEnabled", mapOf("enabled" to true))
        assertEquals(AttriaxDispatchResult.Ok(true), exec(engine, "getEnabled"))

        // Map-shaped getter → Ok(Map) carrying the snapshot fields.
        val snapshot = exec(engine, "getSdkSnapshot")
        assertTrue(snapshot is AttriaxDispatchResult.Ok)
        @Suppress("UNCHECKED_CAST")
        val snapMap = snapshot.value as Map<String, Any?>
        assertEquals(engine.sdkSnapshot.apiVersion, snapMap["apiVersion"])
        assertEquals(engine.sdkSnapshot.packageVersion, snapMap["packageVersion"])

        // Enum-wire getter → the exact snake/lowercase token the C-ABI would emit.
        assertEquals(
            AttriaxDispatchResult.Ok(AttriaxConsentStateWire.toWire(engine.consent.gdpr.state)),
            exec(engine, "getGdprConsentState"),
        )

        // Guard: missing required arg → Err("missing:*"), byte-for-byte the route() error.
        assertEquals(AttriaxDispatchResult.Err("missing:name"), exec(engine, "recordEvent"))

        // Effect: a valid recordEvent returns Ok(null) and enqueues an /events POST.
        val postsBefore = transport.posts.size
        assertEquals(
            AttriaxDispatchResult.Ok(null),
            exec(engine, "recordEvent", mapOf("name" to "level_up", "flushImmediately" to true)),
        )
        assertTrue(
            transport.posts.size > postsBefore,
            "recordEvent(flushImmediately) should have produced a transport POST",
        )

        AttriaxDispatcher.execute(engine, "dispose", emptyMap())
    }

    // -------- adapter equivalence: Unimplemented → the C-ABI error prefix --------

    @Test
    fun unimplementedMapsToTheCApiErrorPrefix() {
        // The C-ABI route() serializes Unimplemented(method) as "unimplemented:<method>";
        // this pins the invariant the golden C-ABI dispatch test relies on.
        val engine = newEngine()
        val result = exec(engine, "nope")
        assertNotNull(result as? AttriaxDispatchResult.Unimplemented)
        assertEquals("nope", result.method)
        AttriaxDispatcher.execute(engine, "dispose", emptyMap())
    }
}
