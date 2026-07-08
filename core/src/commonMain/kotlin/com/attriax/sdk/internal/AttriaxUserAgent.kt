package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxVersion

/**
 * Builds the mandatory, load-bearing SDK User-Agent (PARITY §8 / row W2).
 *
 * The backend runs `isbot` over the UA. The generator default
 * `OpenAPI-Generator/...` — and even the bare `attriax-android-sdk/x` form
 * WITHOUT the parenthetical suffix — trips the bot filter, silently hiding SDK
 * traffic. The UA is ALSO an anonymous-identity key
 * (`sha256(appId, ip, userAgent, dailySalt)`), so it must be stable per install
 * or a drifting UA mints multiple anonymous users per device.
 *
 * Shape:  `attriax-android-sdk/<ver> (Android <osVersion>; <descriptor>)`
 *
 * Pure — the caller supplies [osVersion] and [descriptor] (package name preferred,
 * else device model) so this is unit-testable off-device.
 */
object AttriaxUserAgent {
    fun format(
        osVersion: String,
        descriptor: String,
        packageVersion: String = AttriaxVersion.PACKAGE_VERSION,
    ): String {
        val os = osVersion.ifBlank { "unknown" }
        val desc = descriptor.ifBlank { "unknown" }
        return "attriax-android-sdk/$packageVersion (Android $os; $desc)"
    }
}
