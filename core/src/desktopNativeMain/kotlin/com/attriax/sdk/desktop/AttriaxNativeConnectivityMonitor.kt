package com.attriax.sdk.desktop

import com.attriax.sdk.internal.ConnectivityMonitor
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

/**
 * Kotlin/Native desktop [ConnectivityMonitor] (connectivity-restore
 * re-flush), the native sibling of the JVM
 * [com.attriax.sdk.jvm.AttriaxDesktopConnectivityMonitor] and the Apple
 * `AttriaxAppleConnectivityMonitor` (NWPathMonitor).
 *
 * Native desktop has no push connectivity callback, so this polls the OS via the
 * per-target [attriaxIsNetworkConnected] seam:
 *  - Windows (mingwX64): `InternetGetConnectedState` (wininet) — an instant, local
 *    system-state query, no network round trip.
 *  - Linux (linuxX64): `getifaddrs` — true when a non-loopback interface is UP and
 *    RUNNING with a bound address.
 *
 * On an offline → online edge it invokes
 * [ConnectivityMonitor.Listener.onConnectivityRestored] so the engine re-flushes a
 * queue that stalled while offline. The poll runs OFF the caller thread on a
 * dedicated single-thread coroutine dispatcher (mirroring
 * [AttriaxNativeScheduler]) and never leaks — [unregister] cancels it. All shared
 * state is guarded by [lock].
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class AttriaxNativeConnectivityMonitor(
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : ConnectivityMonitor {

    private val dispatcher = newSingleThreadContext("attriax-connectivity")
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val lock = SynchronizedObject()

    private var listener: ConnectivityMonitor.Listener? = null
    private var lastConnected: Boolean = false
    private var started = false

    override fun isConnected(): Boolean = attriaxIsNetworkConnected()

    override fun register(listener: ConnectivityMonitor.Listener) {
        synchronized(lock) {
            this.listener = listener
            if (started) return
            started = true
            // Seed the baseline so the first poll only fires on a real transition.
            lastConnected = attriaxIsNetworkConnected()
        }
        scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                val nowConnected = attriaxIsNetworkConnected()
                val restored: ConnectivityMonitor.Listener? = synchronized(lock) {
                    val wasConnected = lastConnected
                    lastConnected = nowConnected
                    // Fire only on the offline → online edge (restore re-flush).
                    // Qualified `this@` — inside `launch {}` the bare `this` is the
                    // CoroutineScope receiver, not the monitor.
                    if (!wasConnected && nowConnected) {
                        this@AttriaxNativeConnectivityMonitor.listener
                    } else {
                        null
                    }
                }
                try {
                    restored?.onConnectivityRestored()
                } catch (e: Throwable) {
                    // A listener failure must never crash the host or kill the poll.
                }
            }
        }
    }

    override fun unregister(listener: ConnectivityMonitor.Listener) {
        synchronized(lock) { this.listener = null }
        try {
            scope.cancel()
        } catch (e: Throwable) {
            // best-effort teardown
        }
        try {
            dispatcher.close()
        } catch (e: Throwable) {
            // best-effort teardown
        }
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 15_000L
    }
}

/**
 * Per-target connectivity probe. `true` when the host currently has a usable
 * non-loopback network. Implemented via `InternetGetConnectedState` on Windows and
 * `getifaddrs` on Linux; any failure degrades to `true` (assume online) so a probe
 * error never suppresses sends.
 */
internal expect fun attriaxIsNetworkConnected(): Boolean
