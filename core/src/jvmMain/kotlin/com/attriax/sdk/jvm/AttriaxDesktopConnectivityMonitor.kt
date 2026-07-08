package com.attriax.sdk.jvm

import com.attriax.sdk.internal.ConnectivityMonitor

/**
 * Desktop [ConnectivityMonitor]. A JVM desktop host is generally online and the
 * JDK has no cheap, reliable "connectivity restored" signal equivalent to
 * Android's `ConnectivityManager` callback, so this reports always-connected and
 * treats register/unregister as no-ops (there are no restoration events to fire).
 *
 * Consequence vs. Android: the connectivity-restore re-flush (PARITY §7) never
 * fires here. That is acceptable on desktop — the queue still drains on the normal
 * flush cadence and on the next app-open — and a reachability probe (e.g. opening a
 * socket to the API host) is deliberately out of scope: it would add latency and
 * false negatives behind proxies/captive portals without a real gain, since the
 * transport already surfaces offline sends as retryable transport failures.
 */
class AttriaxDesktopConnectivityMonitor : ConnectivityMonitor {

    override fun isConnected(): Boolean = true

    override fun register(listener: ConnectivityMonitor.Listener) = Unit

    override fun unregister(listener: ConnectivityMonitor.Listener) = Unit
}
