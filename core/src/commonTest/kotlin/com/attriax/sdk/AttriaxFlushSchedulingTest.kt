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
import com.attriax.sdk.internal.queue.AttriaxQueueManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Flush-scheduling coverage for the two DEAD config knobs made live:
 *  - periodic (deferred, coalesced) flush over `eventFlushIntervalMs` (PARITY §7,
 *    Flutter synchronizer `_scheduleDeferredFlush`),
 *  - first-launch eager flush over `flushEventsImmediatelyOnFirstLaunch` (PARITY,
 *    Flutter tracking-manager `_shouldFlushEventImmediately`).
 *
 * Driven through the real [Attriax] engine with a controllable [FakeScheduler]
 * (fires one-shot ticks on demand, no wall clock) and the synchronous fake flush
 * executor so a fired tick drains inline. Session tracking is OFF so the only
 * queue traffic is the init app-open + the events under test.
 */
class AttriaxFlushSchedulingTest {

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

    /** Captures the coalesced one-shot flush; the test fires/cancels it on demand. */
    private class FakeScheduler : AttriaxScheduler {
        var scheduleOnceCount = 0
        var lastDelayMs: Long? = null
        var cancelCount = 0
        private var pending: (() -> Unit)? = null

        override fun schedulePeriodic(intervalMs: Long, action: () -> Unit): AttriaxScheduler.ScheduledHandle =
            AttriaxScheduler.ScheduledHandle { }

        override fun scheduleOnce(delayMs: Long, action: () -> Unit): AttriaxScheduler.ScheduledHandle {
            scheduleOnceCount++
            lastDelayMs = delayMs
            pending = action
            return AttriaxScheduler.ScheduledHandle {
                cancelCount++
                if (pending === action) pending = null
            }
        }

        val hasPending: Boolean get() = pending != null
        fun fire() {
            val action = pending
            pending = null
            action?.invoke()
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
        scheduler: FakeScheduler,
        transport: RecordingTransport = RecordingTransport(),
        store: MapStore = MapStore(),
        firstLaunch: Boolean = false,
        flushEventsImmediatelyOnFirstLaunch: Boolean = true,
    ): Attriax {
        // Pre-mark first-launch completed unless the test wants the eager path, so
        // the periodic-flush behavior is observable in isolation.
        if (!firstLaunch) store.putString("attriax.first_launch_completed", "false")
        val resolver = AttriaxDeviceIdentityResolver(FixedSources("SSAID-123"), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = AttriaxConfig(
                projectToken = "tok",
                sessionTrackingEnabled = false,
                flushEventsImmediatelyOnFirstLaunch = flushEventsImmediatelyOnFirstLaunch,
            ),
            store = store,
            transport = transport,
            connectivity = NoopConnectivity(),
            context = context,
            deviceIdentityStore = identityStore,
            clock = AttriaxClock { 1_000L },
            scheduler = scheduler,
            flushExecutor = AttriaxTestBackgroundExecutor(),
            consentExecutor = AttriaxTestBackgroundExecutor(),
        ).also { it.init() }
    }

    private fun nonOpenPosts(transport: RecordingTransport) =
        transport.posts.filter { !it.first.contains("/open") }

    /** Queued (persisted) non-open requests still awaiting dispatch. */
    private fun pendingNonOpen(store: MapStore) =
        AttriaxQueueManager(store, 500).readAll().filter { !it.request.isAppOpen }

    // -------- Feature 1: periodic (deferred) flush --------

    @Test
    fun nonImmediateEventSchedulesDeferredFlushThatDrainsAfterInterval() {
        val scheduler = FakeScheduler()
        val transport = RecordingTransport()
        val engine = newEngine(scheduler, transport)
        transport.posts.clear()

        engine.tracking.recordEvent("level_up") // non-immediate

        // Armed, not yet flushed: one coalesced one-shot at the configured interval.
        assertEquals(1, scheduler.scheduleOnceCount)
        assertEquals(60_000L, scheduler.lastDelayMs)
        assertTrue(nonOpenPosts(transport).isEmpty(), "must not flush before the interval elapses")

        scheduler.fire() // interval elapsed

        assertEquals(1, nonOpenPosts(transport).size, "deferred flush should dispatch the event")
    }

    @Test
    fun multipleNonImmediateEventsCoalesceToOnePendingFlush() {
        val scheduler = FakeScheduler()
        val transport = RecordingTransport()
        val store = MapStore()
        val engine = newEngine(scheduler, transport, store)
        transport.posts.clear()

        engine.tracking.recordEvent("a")
        engine.tracking.recordEvent("b")
        engine.tracking.recordEvent("c")

        // Coalesced: a single pending flush timer, never stacked; all three buffered.
        assertEquals(1, scheduler.scheduleOnceCount)
        assertTrue(scheduler.hasPending)
        assertEquals(3, pendingNonOpen(store).size, "all three events buffer before the interval")

        scheduler.fire()

        // The single coalesced flush drains the whole buffer (dispatched, possibly batched).
        assertTrue(nonOpenPosts(transport).isNotEmpty(), "deferred flush should dispatch")
        assertTrue(pendingNonOpen(store).isEmpty(), "queue drained after the coalesced flush")
    }

    @Test
    fun immediateEventFlushesNowAndCancelsPendingDeferredFlush() {
        val scheduler = FakeScheduler()
        val transport = RecordingTransport()
        val store = MapStore()
        val engine = newEngine(scheduler, transport, store)
        transport.posts.clear()

        engine.tracking.recordEvent("deferred") // arms the one-shot
        assertTrue(scheduler.hasPending)

        engine.tracking.recordEvent("now", flushImmediately = true) // supersedes

        assertTrue(scheduler.cancelCount >= 1, "an immediate flush must cancel the pending deferred flush")
        // The immediate flush drains both the buffered and the immediate event.
        assertTrue(nonOpenPosts(transport).isNotEmpty(), "immediate flush should dispatch")
        assertTrue(pendingNonOpen(store).isEmpty(), "queue drained by the immediate flush")
    }

    @Test
    fun disabledTrackingSchedulesNothing() {
        val scheduler = FakeScheduler()
        val transport = RecordingTransport()
        val engine = newEngine(scheduler, transport)
        transport.posts.clear()
        engine.enabled = false

        // Engine-level entry bypasses the surface early-return so the scheduler guard
        // itself is exercised; nothing should be armed while tracking is disabled.
        engine.recordEvent("blocked")

        assertEquals(0, scheduler.scheduleOnceCount)
        assertTrue(nonOpenPosts(transport).isEmpty())
    }

    // -------- Feature 2: first-launch eager flush --------

    @Test
    fun firstLaunchEagerFlushDispatchesWithoutWaitingForTheInterval() {
        val scheduler = FakeScheduler()
        val transport = RecordingTransport()
        val engine = newEngine(scheduler, transport, firstLaunch = true)
        transport.posts.clear()

        engine.tracking.recordEvent("first_open") // non-immediate, but first launch

        // Upgraded to an immediate flush: dispatched now, no deferred timer armed.
        assertEquals(0, scheduler.scheduleOnceCount)
        assertEquals(1, nonOpenPosts(transport).size)
    }

    @Test
    fun firstLaunchEagerFlushOffDefersInsteadOfFlushing() {
        val scheduler = FakeScheduler()
        val transport = RecordingTransport()
        val engine = newEngine(
            scheduler,
            transport,
            firstLaunch = true,
            flushEventsImmediatelyOnFirstLaunch = false,
        )
        transport.posts.clear()

        engine.tracking.recordEvent("first_open")

        // Flag off → normal deferred path even on first launch.
        assertEquals(1, scheduler.scheduleOnceCount)
        assertTrue(nonOpenPosts(transport).isEmpty())
    }
}
