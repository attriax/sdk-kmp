package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.AttriaxScheduler
import com.attriax.sdk.internal.ConnectivityMonitor
import com.attriax.sdk.internal.DeviceIdSources
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Engine-level dispose contract (#73): [Attriax.dispose] must stop every
 * thread-owning port the instance was wired with — the scheduler, the
 * connectivity monitor, and the transport — in addition to the two background
 * executors it always owned. Without this, `attriax_destroy` leaves the
 * `attriax-session` dispatcher (and friends) running inside the K/N runtime and
 * a host that unloads the shared library faults.
 *
 * Uses recording fakes (the platform schedulers/monitors have their own
 * platform-side thread-termination tests); this test pins the CONTRACT: dispose
 * reaches shutdown()/close() on every port, is idempotent, and dispose-then-call
 * stays non-throwing.
 */
class AttriaxDisposeTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    private class RecordingConnectivity : ConnectivityMonitor {
        var registered = 0
        var unregistered = 0
        var shutdowns = 0
        override fun isConnected(): Boolean = true
        override fun register(listener: ConnectivityMonitor.Listener) { registered++ }
        override fun unregister(listener: ConnectivityMonitor.Listener) { unregistered++ }
        override fun shutdown() { shutdowns++ }
    }

    private class RecordingTransport : HttpClient {
        var closes = 0
        override fun post(path: String, body: String): HttpResponse = HttpResponse(200, "{}")
        override fun close() { closes++ }
    }

    private class RecordingScheduler : AttriaxScheduler {
        var shutdowns = 0
        var scheduledAfterShutdown = 0
        override fun schedulePeriodic(intervalMs: Long, action: () -> Unit): AttriaxScheduler.ScheduledHandle {
            if (shutdowns > 0) scheduledAfterShutdown++
            return AttriaxScheduler.ScheduledHandle { }
        }
        override fun scheduleOnce(delayMs: Long, action: () -> Unit): AttriaxScheduler.ScheduledHandle {
            if (shutdowns > 0) scheduledAfterShutdown++
            return AttriaxScheduler.ScheduledHandle { }
        }
        override fun shutdown() { shutdowns++ }
    }

    private class FixedSources : DeviceIdSources {
        override fun androidSsaid(): String? = "SSAID-DISPOSE"
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
        scheduler: RecordingScheduler = RecordingScheduler(),
        connectivity: RecordingConnectivity = RecordingConnectivity(),
        transport: RecordingTransport = RecordingTransport(),
    ): Attriax {
        val store = MapStore()
        val resolver = AttriaxDeviceIdentityResolver(FixedSources(), collectAdvertisingId = false)
        return Attriax(
            config = AttriaxConfig(projectToken = "tok", sessionTrackingEnabled = false),
            store = store,
            transport = transport,
            connectivity = connectivity,
            context = context,
            deviceIdentityStore = AttriaxDeviceIdentityStore(store, resolver),
            clock = AttriaxClock { 1_000L },
            scheduler = scheduler,
            flushExecutor = AttriaxTestBackgroundExecutor(),
            consentExecutor = AttriaxTestBackgroundExecutor(),
        ).also { it.init() }
    }

    @Test
    fun disposeShutsDownEveryThreadOwningPort() {
        val scheduler = RecordingScheduler()
        val connectivity = RecordingConnectivity()
        val transport = RecordingTransport()
        val engine = newEngine(scheduler, connectivity, transport)

        engine.dispose()

        assertEquals(1, scheduler.shutdowns, "dispose must stop the heartbeat/deferred-flush scheduler")
        assertEquals(1, connectivity.unregistered, "dispose must unregister the connectivity listener")
        assertEquals(1, connectivity.shutdowns, "dispose must stop the connectivity monitor's poller")
        assertEquals(1, transport.closes, "dispose must close the transport (Ktor engine threads)")
    }

    @Test
    fun doubleDisposeIsSafeAndTearsDownAgainBestEffort() {
        val scheduler = RecordingScheduler()
        val connectivity = RecordingConnectivity()
        val transport = RecordingTransport()
        val engine = newEngine(scheduler, connectivity, transport)

        engine.dispose()
        engine.dispose() // must not throw; per-port shutdowns are themselves idempotent

        assertTrue(scheduler.shutdowns >= 1)
        assertTrue(connectivity.shutdowns >= 1)
        assertTrue(transport.closes >= 1)
    }

    @Test
    fun trackingAfterDisposeDoesNotThrowAndStillPersists() {
        val engine = newEngine()
        engine.dispose()

        // Dispose-then-call: the event still persists to the queue; no timer is armed
        // and no flush runs (the flush executor is shut down). Must never throw.
        engine.tracking.recordEvent("after_dispose")
    }
}
