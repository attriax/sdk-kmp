package com.attriax.sdk.desktop

import com.attriax.sdk.internal.ConnectivityMonitor

/**
 * Kotlin/Native desktop [ConnectivityMonitor], identical in behavior to the JVM
 * [com.attriax.sdk.jvm.AttriaxDesktopConnectivityMonitor]: a desktop host is
 * generally online and there is no cheap, reliable "connectivity restored" signal
 * equivalent to Android's `ConnectivityManager` callback, so this reports
 * always-connected and treats register/unregister as no-ops.
 *
 * Consequence vs. Android: the connectivity-restore re-flush (PARITY §7) never
 * fires here. That is acceptable on desktop — the queue still drains on the normal
 * flush cadence and on the next app-open — and a reachability probe is deliberately
 * out of scope: it would add latency and false negatives behind proxies/captive
 * portals, since the transport already surfaces offline sends as retryable
 * transport failures.
 */
class AttriaxNativeConnectivityMonitor : ConnectivityMonitor {

    override fun isConnected(): Boolean = true

    override fun register(listener: ConnectivityMonitor.Listener) = Unit

    override fun unregister(listener: ConnectivityMonitor.Listener) = Unit
}
