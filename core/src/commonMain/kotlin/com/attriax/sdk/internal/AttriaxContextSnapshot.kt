package com.attriax.sdk.internal

/**
 * App/device/sdk/platform context captured once at init and stamped on
 * open/session payloads (PARITY §1 step 3, §3).
 *
 * Pure value type — the platform layer populates it from Build/PackageManager and
 * passes it in, so request building stays unit-testable.
 */
data class AttriaxContextSnapshot(
    // App
    val packageName: String?,
    val appVersion: String?,
    val appBuildNumber: String?,
    // Device
    val deviceModel: String?,
    val deviceManufacturer: String?,
    val osVersion: String,
    val deviceTimezone: String?,
    val deviceLocale: String?,
    // Platform / SDK
    val platform: String = "android",
    val sdkApiVersion: String = com.attriax.sdk.AttriaxVersion.API_VERSION,
    val sdkPackageVersion: String = com.attriax.sdk.AttriaxVersion.PACKAGE_VERSION,
) {
    /** UA descriptor: package name preferred, else device model, else "unknown". */
    fun userAgentDescriptor(): String =
        packageName?.takeIf { it.isNotBlank() }
            ?: deviceModel?.takeIf { it.isNotBlank() }
            ?: "unknown"
}
