package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.DeviceIdSources
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.attriaxBackgroundExecutor
import com.attriax.sdk.jvm.AttriaxDesktopConnectivityMonitor
import com.attriax.sdk.jvm.AttriaxJvmScheduler
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proof for #73 at the THREAD level: an engine wired with the real JVM
 * scheduler, connectivity monitor, and background executors spins up its
 * `attriax-*` threads, and [Attriax.dispose] terminates ALL of them. Before the
 * fix the `attriax-session` scheduler thread and the `attriax-connectivity`
 * executor thread survived dispose (and, through the C-ABI, `attriax_destroy`),
 * leaking one set of threads per create/destroy cycle and faulting any host
 * that unloads the native library.
 */
class AttriaxDisposeThreadShutdownTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    private class OkTransport : HttpClient {
        override fun post(path: String, body: String): HttpResponse = HttpResponse(200, "{}")
    }

    private class FixedSources : DeviceIdSources {
        override fun androidSsaid(): String? = "SSAID-THREADS"
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

    private fun liveAttriaxThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("attriax-") }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25L)
        }
        return condition()
    }

    @Test
    fun disposeTerminatesEveryEngineThread() {
        // Track by Thread identity: other suites in this JVM leak their own
        // attriax-* threads (engines built with real components and never disposed),
        // so only the threads THIS engine creates count.
        val before = liveAttriaxThreads().toSet()

        val store = MapStore()
        // Pre-mark first-launch completed so the event below takes the DEFERRED path
        // and actually arms the attriax-session timer (a first-launch event would be
        // upgraded to an immediate flush and never touch the scheduler).
        store.putString("attriax.first_launch_completed", "false")
        val resolver = AttriaxDeviceIdentityResolver(FixedSources(), collectAdvertisingId = false)
        val engine = Attriax(
            config = AttriaxConfig(projectToken = "tok", sessionTrackingEnabled = false),
            store = store,
            transport = OkTransport(),
            connectivity = AttriaxDesktopConnectivityMonitor(),
            context = context,
            deviceIdentityStore = AttriaxDeviceIdentityStore(store, resolver),
            clock = AttriaxClock { 1_000L },
            scheduler = AttriaxJvmScheduler(),
            flushExecutor = attriaxBackgroundExecutor("attriax-flush"),
            consentExecutor = attriaxBackgroundExecutor("attriax-consent"),
        )

        // init registers the connectivity poll (attriax-connectivity) and flushes the
        // app-open (attriax-flush); a non-immediate event arms the deferred flush
        // timer (attriax-session).
        engine.init()
        engine.tracking.recordEvent("spin_up_the_session_timer")

        // All three expected threads must be visibly live before dispose so the
        // termination assertion below covers each of them.
        val expected = setOf("attriax-session", "attriax-flush", "attriax-connectivity")
        assertTrue(
            waitUntil(5_000L) {
                liveAttriaxThreads().filterNot { it in before }.map { it.name }.toSet() == expected
            },
            "the engine should have spun up $expected before dispose; saw: " +
                liveAttriaxThreads().filterNot { it in before }.map { it.name },
        )
        val created = liveAttriaxThreads().filterNot { it in before }

        engine.dispose()

        assertTrue(
            waitUntil(5_000L) { created.none { it.isAlive } },
            "dispose must terminate every engine thread; still alive: " +
                created.filter { it.isAlive }.joinToString { it.name },
        )
    }
}
