package com.attriax.sdk

/**
 * Wrapper-supplied device context (PARITY — Flutter `DeviceContextDto`).
 *
 * A host wrapper (Flutter / Unity / React Native over this core) can supply the
 * full device field set via [AttriaxConfig.deviceContext]. Wrappers know their
 * runtime best, so every value here WINS over the core's Android auto-capture
 * (see the platform factory's `captureContext`). Wire field names match
 * `DeviceContextDto` exactly; the fields flow into the app-open `device` block.
 *
 * The five [model]/[manufacturer]/[osVersion]/[timezone]/[language] are REQUIRED
 * (they always emit). Every other field is OPTIONAL and is omitted from the wire
 * when null.
 *
 * [advertisingId]/[androidId] are device-identity fields that the core never
 * auto-collects into the device block — supply them here only if the wrapper has
 * already resolved them and wants them carried on `DeviceContextDto` (matching
 * Flutter, whose DTO carries both).
 */
data class AttriaxDeviceContext(
    // Required — always present on the wire.
    val model: String,
    val manufacturer: String,
    val osVersion: String,
    val timezone: String,
    val language: String,
    // Optional enrichment — omit-if-null on the wire.
    val brand: String? = null,
    val hardware: String? = null,
    val name: String? = null,
    val isPhysicalDevice: Boolean? = null,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
    val screenResolution: String? = null,
    val devicePixelRatio: Double? = null,
    val colorDepth: Int? = null,
    val supportedAbis: List<String>? = null,
    val metadata: Map<String, Any?>? = null,
    // Device-identity — wrapper-supplied ONLY; never auto-captured by the core.
    val advertisingId: String? = null,
    val androidId: String? = null,
)
