package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.AttriaxSynchronizationStateHolder
import com.attriax.sdk.internal.AttriaxTransportException
import com.attriax.sdk.internal.ConnectivityMonitor
import com.attriax.sdk.internal.DeviceIdSources
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Synchronization-state observability (PARITY — Flutter reference
 * `AttriaxSynchronizer`). Drives the real [Attriax] engine through its dispatch
 * lifecycle with synchronous fakes and asserts the state transitions +
 * listener fan-out. Session tracking is OFF so the only init traffic is the
 * app-open. Deterministic on jvm AND native.
 */
class AttriaxSynchronizationStateTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    private class NoopConnectivity : ConnectivityMonitor {
        override fun isConnected(): Boolean = true
        override fun register(listener: ConnectivityMonitor.Listener) {}
        override fun unregister(listener: ConnectivityMonitor.Listener) {}
    }

    private class OkTransport : HttpClient {
        override fun post(path: String, body: String): HttpResponse = HttpResponse(200, "{}")
    }

    /** Always fails with a retryable transport error → items re-queue with lastErrorClass. */
    private class FailingTransport : HttpClient {
        override fun post(path: String, body: String): HttpResponse =
            throw AttriaxTransportException("boom")
    }

    private class FixedSources(private val ssaid: String?) : DeviceIdSources {
        override fun androidSsaid(): String? = ssaid
        override fun advertisingId(): String? = null
    }

    private class RecordingListener : AttriaxSynchronizationStateListener {
        val states = mutableListOf<AttriaxSynchronizationState>()
        override fun onSynchronizationStateChanged(state: AttriaxSynchronizationState) {
            states.add(state)
        }
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
        transport: HttpClient = OkTransport(),
        store: MapStore = MapStore(),
        gdprEnabled: Boolean = false,
        anonymousTracking: Boolean = true,
        init: Boolean = false,
    ): Attriax {
        store.putString("attriax.first_launch_completed", "false")
        val resolver = AttriaxDeviceIdentityResolver(FixedSources("SSAID-123"), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = AttriaxConfig(
                projectToken = "tok",
                sessionTrackingEnabled = false,
                gdprEnabled = gdprEnabled,
                anonymousTracking = anonymousTracking,
            ),
            store = store,
            transport = transport,
            connectivity = NoopConnectivity(),
            context = context,
            deviceIdentityStore = identityStore,
            clock = AttriaxClock { 1_000L },
            flushExecutor = AttriaxTestBackgroundExecutor(),
            consentExecutor = AttriaxTestBackgroundExecutor(),
        ).also { if (init) it.init() }
    }

    // -------- engine-driven lifecycle --------

    @Test
    fun flushSequenceIsInitializingThenSynchronizingThenSynchronized() {
        val engine = newEngine()
        val listener = RecordingListener()
        engine.synchronization.addStateListener(listener)

        // Before init: initializing, not yet synchronized.
        assertEquals(AttriaxSynchronizationState.INITIALIZING, engine.synchronization.state)
        assertFalse(engine.synchronization.isSynchronized)

        engine.init() // enqueues + flushes the app-open synchronously

        assertEquals(
            listOf(
                AttriaxSynchronizationState.SYNCHRONIZING,
                AttriaxSynchronizationState.SYNCHRONIZED,
            ),
            listener.states,
        )
        assertEquals(AttriaxSynchronizationState.SYNCHRONIZED, engine.synchronization.state)
        assertTrue(engine.synchronization.isSynchronized)
    }

    @Test
    fun offlineWhenTransportFailsWithANonEmptyQueue() {
        val engine = newEngine(transport = FailingTransport(), init = true)

        // The app-open flush failed (retryable) → it stays queued with lastErrorClass.
        assertEquals(AttriaxSynchronizationState.OFFLINE, engine.synchronization.state)
        assertFalse(engine.synchronization.isSynchronized)
    }

    @Test
    fun deferredWhenConsentDefersNetworkDispatch() {
        // gdpr on + anonymous off + waiting → capture buffers locally (deferNetwork).
        val engine = newEngine(gdprEnabled = true, anonymousTracking = false, init = true)
        assertTrue(engine.isWaitingForGdprConsent)

        engine.tracking.recordEvent("level_up")

        assertEquals(AttriaxSynchronizationState.DEFERRED, engine.synchronization.state)
        assertFalse(engine.synchronization.isSynchronized)
    }

    @Test
    fun disabledWhenTrackingIsTurnedOff() {
        val engine = newEngine(init = true)
        assertEquals(AttriaxSynchronizationState.SYNCHRONIZED, engine.synchronization.state)

        engine.enabled = false

        assertEquals(AttriaxSynchronizationState.DISABLED, engine.synchronization.state)
        assertFalse(engine.synchronization.isSynchronized)
    }

    @Test
    fun resetReturnsToInitializing() {
        val engine = newEngine(init = true)
        assertEquals(AttriaxSynchronizationState.SYNCHRONIZED, engine.synchronization.state)

        engine.reset()

        assertEquals(AttriaxSynchronizationState.INITIALIZING, engine.synchronization.state)
    }

    @Test
    fun removedListenerReceivesNoFurtherTransitions() {
        val engine = newEngine(init = true)
        val listener = RecordingListener()
        engine.synchronization.addStateListener(listener)
        engine.synchronization.removeStateListener(listener)

        engine.flush() // drives synchronizing → synchronized again

        assertTrue(listener.states.isEmpty(), "a removed listener must not be notified")
    }

    // -------- holder-level dedupe (transition only on actual change) --------

    @Test
    fun holderEmitsOnlyOnActualStateChange() {
        val holder = AttriaxSynchronizationStateHolder()
        val listener = RecordingListener()
        holder.addListener(listener)

        holder.set(AttriaxSynchronizationState.SYNCHRONIZING)
        holder.set(AttriaxSynchronizationState.SYNCHRONIZING) // duplicate → suppressed
        holder.set(AttriaxSynchronizationState.SYNCHRONIZED)
        holder.set(AttriaxSynchronizationState.SYNCHRONIZED) // duplicate → suppressed

        assertEquals(
            listOf(
                AttriaxSynchronizationState.SYNCHRONIZING,
                AttriaxSynchronizationState.SYNCHRONIZED,
            ),
            listener.states,
        )
        assertTrue(holder.isSynchronized)
    }
}
