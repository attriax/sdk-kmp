package com.attriax.sdk

/**
 * SDK version constants.
 *
 * These are shipped on session/crash payloads as `sdkApiVersion` / `sdkPackageVersion`
 * and are load-bearing for the wire User-Agent (see [AttriaxUserAgent]).
 */
object AttriaxVersion {
    /** Wire API version segment (`/api/sdk/v1/...`). */
    const val API_VERSION: String = "v1"

    /**
     * SDK package/release version.
     *
     * MUST be kept in lockstep with `ATTRIAX_VERSION` in `gradle.properties` (the
     * published `com.attriax:core` coordinate). This constant is what a device
     * actually reports — it is stamped into the wire User-Agent and onto every
     * session/crash payload as `sdkPackageVersion`. If it drifts behind the
     * published version, the backend cannot distinguish which SDK build a device is
     * running, which is exactly what a fix release like 0.6.1 needs to be able to
     * tell apart from the defective 0.6.0. (`sdk-react-native` shipped this bug:
     * a hardcoded `0.5.0` in its User-Agent while the package was `0.6.0`.)
     */
    const val PACKAGE_VERSION: String = "0.6.2"
}
