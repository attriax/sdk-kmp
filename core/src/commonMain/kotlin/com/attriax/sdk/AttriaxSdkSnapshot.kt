package com.attriax.sdk

/**
 * SDK version + metadata snapshot exposed by [Attriax.sdkSnapshot].
 * Mirrors the Flutter reference `AttriaxSdkSnapshot`
 * (attriax_flutter_platform_interface types_platform_runtime.dart:149-165):
 * `apiVersion`, `packageVersion`, and the SDK `metadata` block (default empty).
 *
 * Flutter's getter is nullable (null until `init` captures the runtime state); the
 * KMP core captures the context snapshot at construction, so [Attriax.sdkSnapshot]
 * is always available (non-null) — a documented, safe difference.
 */
data class AttriaxSdkSnapshot(
    val apiVersion: String,
    val packageVersion: String,
    val metadata: Map<String, Any?> = emptyMap(),
)
