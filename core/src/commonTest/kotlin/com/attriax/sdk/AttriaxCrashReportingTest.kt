package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxCrashReportingManager
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.AttriaxUncaughtHandlerRegistration
import com.attriax.sdk.internal.ConnectivityMonitor
import com.attriax.sdk.internal.DeviceIdSources
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.queue.AttriaxQueueManager
import com.attriax.sdk.internal.request.AttriaxApiRequest
import com.attriax.sdk.internal.request.AttriaxRequestBuilders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Automatic crash reporting coverage (Flutter
 * `AttriaxCrashReportingManager`). Driven through the real [Attriax] engine with the
 * same fakes the tracking/flush tests use, plus a [FakeUncaughtInstaller] that
 * captures the `onFatalCrash` callback so the OS-handler path is exercised WITHOUT
 * crashing the test VM. Session tracking is off and the default no-op scheduler is
 * used, so the only queue traffic is the init app-open (flushed synchronously) plus
 * the crashes under test (buffered, inspectable).
 */
class AttriaxCrashReportingTest {

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

    /** Captures the installed OS uncaught-exception callback; never touches the VM handler. */
    private class FakeUncaughtInstaller {
        var installed = 0
        var uninstalled = 0
        var onFatalCrash: ((Throwable) -> Unit)? = null

        fun asSeam(): (onFatalCrash: (Throwable) -> Unit) -> AttriaxUncaughtHandlerRegistration = { cb ->
            installed++
            onFatalCrash = cb
            object : AttriaxUncaughtHandlerRegistration {
                override fun uninstall() {
                    uninstalled++
                    onFatalCrash = null
                }
            }
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
        store: MapStore = MapStore(),
        transport: RecordingTransport = RecordingTransport(),
        installer: FakeUncaughtInstaller = FakeUncaughtInstaller(),
        automaticCrashReportingEnabled: Boolean = true,
    ): Attriax {
        // Not first launch, so the first-launch eager flush never masks the buffering.
        store.putString("attriax.first_launch_completed", "false")
        val resolver = AttriaxDeviceIdentityResolver(FixedSources("SSAID-123"), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = AttriaxConfig(
                projectToken = "tok",
                sessionTrackingEnabled = false,
                automaticCrashReportingEnabled = automaticCrashReportingEnabled,
            ),
            store = store,
            transport = transport,
            connectivity = NoopConnectivity(),
            context = context,
            deviceIdentityStore = identityStore,
            clock = AttriaxClock { 1_000L },
            flushExecutor = AttriaxTestBackgroundExecutor(),
            consentExecutor = AttriaxTestBackgroundExecutor(),
            installUncaughtExceptionHandler = installer.asSeam(),
        ).also { it.init() }
    }

    private fun crashRequests(store: MapStore): List<AttriaxApiRequest> =
        AttriaxQueueManager(store, 500).readAll()
            .map { it.request }
            .filter { it.kind == AttriaxApiRequest.KIND_TRACK_CRASH }

    /** A ready-to-persist crash record (the crash request body JSON) with [isFatal]. */
    private fun seedPendingCrash(store: MapStore, isFatal: Boolean = true) {
        val request = AttriaxRequestBuilders.buildCrash(
            projectToken = "tok",
            context = context,
            deviceId = "SSAID-123",
            deviceIdSource = "ssaid",
            source = "uncaught_exception",
            isFatal = isFatal,
            exceptionType = "com.example.BoomException",
            message = "boom",
            stackTrace = "at com.example.Boom.kt:1",
            isFirstLaunch = false,
            clientOccurredAtIso = "2026-07-08T00:00:00.000Z",
            reason = null,
            sessionId = null,
            sessionRelativeTimeMs = null,
            metadata = null,
        )
        store.putString(AttriaxCrashReportingManager.KEY_PENDING_CRASH, Json.encode(request.body))
    }

    // -------- replay-on-init (_replayPendingCrashReport) --------

    @Test
    fun initReplaysPendingCrashOnceAndClearsRecord() {
        val store = MapStore()
        seedPendingCrash(store, isFatal = true)

        newEngine(store)

        val crashes = crashRequests(store)
        assertEquals(1, crashes.size, "the pending crash should be enqueued exactly once")
        val body = crashes.single().body
        assertEquals(true, body["isFatal"])
        assertEquals("com.example.BoomException", body["exceptionType"])
        assertEquals("boom", body["message"])
        assertEquals("uncaught_exception", body["source"])
        assertNull(
            store.getString(AttriaxCrashReportingManager.KEY_PENDING_CRASH),
            "the record must be cleared after replay (one-shot)",
        )
    }

    @Test
    fun initWithNoPendingCrashEnqueuesNothing() {
        val store = MapStore()

        newEngine(store)

        assertTrue(crashRequests(store).isEmpty(), "no pending record → no crash enqueued")
    }

    // -------- fatal report: persist-ONLY, delivered exactly once via replay on next init --------

    @Test
    fun fatalReportPersistsWithoutEnqueuingThenReplaysExactlyOnceOnRestart() {
        val store = MapStore()
        val engine = newEngine(store)

        engine.tracking.recordError(IllegalStateException("kaboom"), fatal = true)

        // Persisted for durability but NOT enqueued now — the queue is unchanged right
        // after the call (no double-send in the happy path).
        val persisted = store.getString(AttriaxCrashReportingManager.KEY_PENDING_CRASH)
        assertNotNull(persisted, "a fatal report must persist a crash record")
        assertTrue(
            crashRequests(store).isEmpty(),
            "a fatal report must NOT immediately enqueue — delivery is via replay only",
        )

        // Restart on the SAME store (persistent storage survives): a fresh engine replays
        // the persisted record exactly once (enqueued into the durable queue) and clears it.
        newEngine(store)

        val replayed = crashRequests(store)
        assertEquals(1, replayed.size, "the persisted record replays exactly once")
        assertEquals(true, replayed.single().body["isFatal"])
        assertNull(
            store.getString(AttriaxCrashReportingManager.KEY_PENDING_CRASH),
            "replay clears the record",
        )
    }

    // -------- automatic OS handler --------

    @Test
    fun installedHandlerCallbackPersistsCrashRecordWithoutEnqueuing() {
        val store = MapStore()
        val installer = FakeUncaughtInstaller()
        newEngine(store, installer = installer)

        assertEquals(1, installer.installed, "the handler is installed when enabled")
        val callback = installer.onFatalCrash
        assertNotNull(callback, "the onFatalCrash callback must be captured")

        // Drive the callback directly (no real crash): it persists synchronously only.
        callback.invoke(IllegalStateException("uncaught"))

        val persisted = store.getString(AttriaxCrashReportingManager.KEY_PENDING_CRASH)
        assertNotNull(persisted, "the OS handler must persist a crash record synchronously")
        @Suppress("UNCHECKED_CAST")
        val body = Json.decode(persisted) as Map<String, Any?>
        assertEquals(true, body["isFatal"])
        assertEquals("uncaught_exception", body["source"])
        assertTrue(
            (body["exceptionType"] as String).contains("IllegalStateException"),
            "exceptionType should name the thrown type",
        )
        // The dying-process handler persists ONLY — recovery is via replay on next init.
        assertTrue(crashRequests(store).isEmpty(), "the OS handler must not enqueue")
    }

    @Test
    fun disabledCrashReportingInstallsNoHandlerAndSkipsReplay() {
        val store = MapStore()
        seedPendingCrash(store, isFatal = true)
        val installer = FakeUncaughtInstaller()

        newEngine(store, installer = installer, automaticCrashReportingEnabled = false)

        assertEquals(0, installer.installed, "no handler installed when disabled")
        assertNull(installer.onFatalCrash)
        assertTrue(crashRequests(store).isEmpty(), "disabled runtime must not replay")
        assertNotNull(
            store.getString(AttriaxCrashReportingManager.KEY_PENDING_CRASH),
            "a disabled runtime neither replays nor clears the record",
        )
    }

    // -------- non-fatal recordError unchanged --------

    @Test
    fun nonFatalRecordErrorEnqueuesWithoutPersisting() {
        val store = MapStore()
        val engine = newEngine(store)

        engine.tracking.recordError(IllegalStateException("oops"), fatal = false)

        val crashes = crashRequests(store)
        assertEquals(1, crashes.size, "a non-fatal error is enqueued")
        assertEquals(false, crashes.single().body["isFatal"])
        assertEquals("oops", crashes.single().body["message"])
        assertNull(
            store.getString(AttriaxCrashReportingManager.KEY_PENDING_CRASH),
            "a non-fatal error must never persist a crash record",
        )
    }

    @Test
    fun disabledTrackingIgnoresRecordError() {
        val store = MapStore()
        val engine = newEngine(store)
        engine.enabled = false

        engine.tracking.recordError(IllegalStateException("ignored"), fatal = true)

        assertTrue(crashRequests(store).isEmpty(), "disabled tracking drops recordError")
        assertNull(store.getString(AttriaxCrashReportingManager.KEY_PENDING_CRASH))
    }
}
