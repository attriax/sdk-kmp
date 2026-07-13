package com.attriax.sdk.apple

import com.attriax.sdk.internal.ConnectivityMonitor
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * [ConnectivityMonitor] backed by `NWPathMonitor` (the C `nw_path_monitor` API) —
 * the Apple sibling of the Android `ConnectivityManager` monitor.
 *
 * Invokes [ConnectivityMonitor.Listener.onConnectivityRestored] when the network
 * path transitions unsatisfied → satisfied so the engine re-flushes a queue that
 * stalled while offline. The update handler runs on a dedicated serial
 * dispatch queue; all shared state is guarded by [lock].
 */
@OptIn(ExperimentalForeignApi::class)
class AttriaxAppleConnectivityMonitor : ConnectivityMonitor {

    private val monitor = nw_path_monitor_create()
    private val queue = dispatch_queue_create("com.attriax.sdk.connectivity", null)
    private val lock = SynchronizedObject()

    // Optimistically start connected; NWPathMonitor delivers the current path to the
    // update handler immediately on start, correcting this before the first flush.
    private var connected = true
    private var listener: ConnectivityMonitor.Listener? = null
    private var started = false

    override fun isConnected(): Boolean = synchronized(lock) { connected }

    override fun register(listener: ConnectivityMonitor.Listener) {
        synchronized(lock) {
            this.listener = listener
            if (started) return
            started = true
        }
        nw_path_monitor_set_update_handler(monitor) { path ->
            val nowConnected = nw_path_get_status(path) == nw_path_status_satisfied
            val restored: ConnectivityMonitor.Listener? = synchronized(lock) {
                val wasConnected = connected
                connected = nowConnected
                // Fire only on the offline → online edge (restore re-flush).
                if (!wasConnected && nowConnected) this.listener else null
            }
            restored?.onConnectivityRestored()
        }
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_start(monitor)
    }

    override fun unregister(listener: ConnectivityMonitor.Listener) {
        synchronized(lock) { this.listener = null }
        nw_path_monitor_cancel(monitor)
    }
}
