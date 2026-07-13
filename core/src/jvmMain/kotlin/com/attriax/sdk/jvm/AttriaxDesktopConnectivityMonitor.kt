package com.attriax.sdk.jvm

import com.attriax.sdk.internal.ConnectivityMonitor
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * JVM-desktop [ConnectivityMonitor] (connectivity-restore re-flush).
 *
 * The JDK has no push callback equivalent to Android's `ConnectivityManager`, so this
 * detects connectivity by polling [NetworkInterface]: the host is "connected" when at
 * least one non-loopback interface is `isUp` and carries a bound address. On an
 * offline → online edge the monitor invokes
 * [ConnectivityMonitor.Listener.onConnectivityRestored], matching the Android
 * ([com.attriax.sdk.android.AttriaxConnectivityMonitor]) and Apple
 * (`AttriaxAppleConnectivityMonitor`, NWPathMonitor) actuals so the engine re-flushes
 * a queue that stalled while offline.
 *
 * The poll runs OFF the caller thread on a single daemon
 * [ScheduledExecutorService] and never leaks — [unregister] stops it once the last
 * listener is gone. It is deliberately pure-JDK (no reachability probe / socket
 * connect): a local interface scan has no latency and no captive-portal false
 * negatives, and the transport already surfaces genuine offline sends as retryable
 * transport failures.
 */
class AttriaxDesktopConnectivityMonitor(
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) : ConnectivityMonitor {

    private val listeners = CopyOnWriteArrayList<ConnectivityMonitor.Listener>()

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "attriax-connectivity").apply { isDaemon = true }
        }

    // Guarded by `this`. Last observed connectivity, for offline → online edge detection.
    private var lastConnected: Boolean = false
    private var poll: ScheduledFuture<*>? = null

    override fun isConnected(): Boolean = hasActiveInterface()

    @Synchronized
    override fun register(listener: ConnectivityMonitor.Listener) {
        listeners.addIfAbsent(listener)
        if (poll == null) {
            // Seed the baseline so the first poll only fires on a real transition.
            lastConnected = hasActiveInterface()
            poll = executor.scheduleAtFixedRate(
                { pollOnce() },
                pollIntervalMs,
                pollIntervalMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    @Synchronized
    override fun unregister(listener: ConnectivityMonitor.Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            poll?.cancel(false)
            poll = null
        }
    }

    private fun pollOnce() {
        val nowConnected = hasActiveInterface()
        val restored = synchronized(this) {
            val wasConnected = lastConnected
            lastConnected = nowConnected
            // Fire only on the offline → online edge (restore re-flush).
            !wasConnected && nowConnected
        }
        if (restored) {
            listeners.forEach {
                try {
                    it.onConnectivityRestored()
                } catch (e: Exception) {
                    // A listener failure must never crash the host or kill the poll.
                }
            }
        }
    }

    /** True when any non-loopback interface is up and carries a bound address. */
    private fun hasActiveInterface(): Boolean = try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
        interfaces.asSequence().any { ni ->
            !ni.isLoopback && ni.isUp && ni.inetAddresses.hasMoreElements()
        }
    } catch (e: Exception) {
        // Enumeration can fail transiently (e.g. SocketException); treat as offline
        // rather than throwing into the poll thread.
        false
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 15_000L
    }
}
