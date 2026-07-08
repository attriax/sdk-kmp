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
    // Device — the "required five" that always emit in the open `device` block
    // (osVersion is non-null; the others emit whenever present, as today).
    val deviceModel: String?,
    val deviceManufacturer: String?,
    val osVersion: String,
    val deviceTimezone: String?,
    val deviceLocale: String?,
    // Device enrichment (PARITY — Flutter DeviceContextDto). All OPTIONAL: omitted
    // from the wire when null. Wire field names match DeviceContextDto exactly.
    // Populated on Android by the platform factory (auto-capture) and/or supplied by
    // a wrapper via [com.attriax.sdk.AttriaxDeviceContext] (wrapper value wins).
    val deviceBrand: String? = null,
    val deviceHardware: String? = null,
    val deviceName: String? = null,
    val deviceIsPhysical: Boolean? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val screenResolution: String? = null,
    val devicePixelRatio: Double? = null,
    val colorDepth: Int? = null,
    val supportedAbis: List<String>? = null,
    val deviceMetadata: Map<String, Any?>? = null,
    // Device-identity enrichment carried by DeviceContextDto (advertisingId/androidId).
    // NEVER auto-captured into this block — device identity flows through the identity
    // resolver. Present here ONLY when a wrapper explicitly supplies it.
    val advertisingId: String? = null,
    val androidId: String? = null,
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

/**
 * Overlay a wrapper-supplied [com.attriax.sdk.AttriaxDeviceContext] onto this
 * snapshot. The wrapper WINS over any auto-captured value: the required five are
 * always taken from the wrapper (non-null there), and each optional enrichment
 * field overrides the snapshot only when the wrapper supplies a non-null value
 * (otherwise the auto-captured value is preserved). Returns `this` unchanged when
 * [deviceContext] is null. `advertisingId`/`androidId` come solely from the wrapper.
 */
internal fun AttriaxContextSnapshot.withDeviceContext(
    deviceContext: com.attriax.sdk.AttriaxDeviceContext?,
): AttriaxContextSnapshot {
    if (deviceContext == null) return this
    return copy(
        deviceModel = deviceContext.model,
        deviceManufacturer = deviceContext.manufacturer,
        osVersion = deviceContext.osVersion,
        deviceTimezone = deviceContext.timezone,
        deviceLocale = deviceContext.language,
        deviceBrand = deviceContext.brand ?: deviceBrand,
        deviceHardware = deviceContext.hardware ?: deviceHardware,
        deviceName = deviceContext.name ?: deviceName,
        deviceIsPhysical = deviceContext.isPhysicalDevice ?: deviceIsPhysical,
        screenWidth = deviceContext.screenWidth ?: screenWidth,
        screenHeight = deviceContext.screenHeight ?: screenHeight,
        screenResolution = deviceContext.screenResolution ?: screenResolution,
        devicePixelRatio = deviceContext.devicePixelRatio ?: devicePixelRatio,
        colorDepth = deviceContext.colorDepth ?: colorDepth,
        supportedAbis = deviceContext.supportedAbis ?: supportedAbis,
        deviceMetadata = deviceContext.metadata ?: deviceMetadata,
        advertisingId = deviceContext.advertisingId ?: advertisingId,
        androidId = deviceContext.androidId ?: androidId,
    )
}
