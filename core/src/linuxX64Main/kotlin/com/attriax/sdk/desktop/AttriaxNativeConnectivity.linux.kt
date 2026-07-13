package com.attriax.sdk.desktop

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.linux.freeifaddrs
import platform.linux.getifaddrs
import platform.linux.ifaddrs

/**
 * Linux-native connectivity probe. Enumerates interfaces via
 * `getifaddrs` (bound in `platform.linux`) and reports connected when any
 * non-loopback interface is UP and RUNNING (carrier present) with a bound address.
 * Purely local (no reachability round trip). Any failure — including `getifaddrs`
 * returning non-zero — degrades to `true` (assume online) so a probe error never
 * suppresses sends.
 *
 * The `IFF_*` flag values are part of the stable Linux ABI, so they are inlined as
 * literals to keep this free of any per-distro constant-binding surprises:
 * `IFF_UP = 0x1`, `IFF_LOOPBACK = 0x8`, `IFF_RUNNING = 0x40`.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxIsNetworkConnected(): Boolean = try {
    memScoped {
        val head = alloc<CPointerVar<ifaddrs>>()
        if (getifaddrs(head.ptr) != 0) return@memScoped true
        try {
            var node = head.value
            var connected = false
            while (node != null) {
                val ifa = node.pointed
                val flags = ifa.ifa_flags.toInt()
                val isUp = flags and IFF_UP != 0
                val isRunning = flags and IFF_RUNNING != 0
                val isLoopback = flags and IFF_LOOPBACK != 0
                if (isUp && isRunning && !isLoopback && ifa.ifa_addr != null) {
                    connected = true
                    break
                }
                node = ifa.ifa_next
            }
            connected
        } finally {
            freeifaddrs(head.value)
        }
    }
} catch (e: Throwable) {
    true
}

private const val IFF_UP = 0x1
private const val IFF_LOOPBACK = 0x8
private const val IFF_RUNNING = 0x40
