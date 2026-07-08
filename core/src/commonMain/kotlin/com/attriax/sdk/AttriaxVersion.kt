package com.attriax.sdk

/**
 * SDK version constants (PARITY §1 / row I3).
 *
 * Mirrors the Flutter reference `types.dart:8-9`:
 *   attriaxSdkApiVersion = 'v1'
 *   attriaxSdkPackageVersion = '0.5.0'
 *
 * These are shipped on session/crash payloads as `sdkApiVersion` / `sdkPackageVersion`
 * and are load-bearing for the wire User-Agent (see [AttriaxUserAgent]).
 */
object AttriaxVersion {
    /** Wire API version segment (`/api/sdk/v1/...`). */
    const val API_VERSION: String = "v1"

    /** SDK package/release version. Kept in lockstep with the Flutter reference. */
    const val PACKAGE_VERSION: String = "0.5.0"
}
