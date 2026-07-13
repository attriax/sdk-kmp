package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxVersion

/**
 * Builds the mandatory, load-bearing SDK User-Agent.
 *
 * The backend runs `isbot` over the UA. The generator default
 * `OpenAPI-Generator/...` — and even the bare `attriax-android-sdk/x` form
 * WITHOUT the parenthetical suffix — trips the bot filter, silently hiding SDK
 * traffic. The UA is ALSO an anonymous-identity key
 * (`sha256(appId, ip, userAgent, dailySalt)`), so it must be stable per install
 * or a drifting UA mints multiple anonymous users per device.
 *
 * Shape:  `<client>/<ver> (<osName> <osVersion>; <descriptor>)`
 *   Android: `attriax-android-sdk/<ver> (Android <osVersion>; <descriptor>)`
 *   JVM desktop: `attriax-jvm-sdk/<ver> (Windows <osVersion>; <descriptor>)`
 *
 * [client] and [osName] default to the Android values so existing callers are
 * unchanged; the JVM desktop factory overrides them (`attriax-jvm-sdk` / the real
 * `os.name`) so the UA is honest per platform while still carrying the mandatory,
 * isbot-passing parenthetical suffix.
 *
 * Pure — the caller supplies [osVersion] and [descriptor] (package name preferred,
 * else device model) so this is unit-testable off-device.
 */
object AttriaxUserAgent {
    fun format(
        osVersion: String,
        descriptor: String,
        packageVersion: String = AttriaxVersion.PACKAGE_VERSION,
        client: String = "attriax-android-sdk",
        osName: String = "Android",
    ): String {
        val os = osVersion.ifBlank { "unknown" }
        val desc = descriptor.ifBlank { "unknown" }
        val name = osName.ifBlank { "unknown" }
        val slug = client.ifBlank { "attriax-sdk" }
        return "$slug/$packageVersion ($name $os; $desc)"
    }
}
