package com.attriax.sdk.desktop

import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.InternetGetConnectedState

/**
 * Windows-native connectivity probe. Uses `InternetGetConnectedState`
 * (wininet, already linked by `platform.windows`) — an instant, local query of the
 * system connection state (no network round trip, no latency). A non-zero `BOOL`
 * means the machine reports an active internet connection. Any unexpected failure
 * degrades to `true` (assume online) so a probe error never suppresses sends.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxIsNetworkConnected(): Boolean = try {
    InternetGetConnectedState(null, 0u) != 0
} catch (e: Throwable) {
    true
}
