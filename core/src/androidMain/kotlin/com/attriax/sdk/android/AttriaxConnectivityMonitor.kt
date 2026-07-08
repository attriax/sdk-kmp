package com.attriax.sdk.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.attriax.sdk.internal.ConnectivityMonitor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [ConnectivityMonitor] backed by [ConnectivityManager] (PARITY §7 — connectivity
 * restore re-flushes). Mechanism differs from Flutter's `connectivity_plus`, but
 * the behavior (fire on regain) matches.
 */
class AttriaxConnectivityMonitor(context: Context) : ConnectivityMonitor {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val listeners = CopyOnWriteArrayList<ConnectivityMonitor.Listener>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            listeners.forEach { it.onConnectivityRestored() }
        }
    }

    private var registered = false

    override fun isConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Synchronized
    override fun register(listener: ConnectivityMonitor.Listener) {
        listeners.addIfAbsent(listener)
        if (!registered) {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            registered = true
        }
    }

    @Synchronized
    override fun unregister(listener: ConnectivityMonitor.Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty() && registered) {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            registered = false
        }
    }
}
