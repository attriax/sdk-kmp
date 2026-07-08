package com.attriax.sdk

/**
 * Immutable SDK configuration (PARITY §1, rows I2/I3).
 *
 * Defaults mirror the Flutter reference `AttriaxConfig`
 * (`types_session_config.dart:101-248`). Durations are expressed in
 * milliseconds so the whole type is a plain value with no platform or
 * time-library dependency (unit-testable).
 *
 * `projectToken` is trimmed here; an empty token is retained as-is and the
 * transport throws when it is asked to send with an empty token (matching the
 * Flutter behavior where the token is required and empty → transport throws).
 */
data class AttriaxConfig(
    val projectToken: String,
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    val appVersion: String? = null,
    val appBuildNumber: String? = null,
    val appPackageName: String? = null,
    /**
     * Extra SDK metadata attached to the runtime context payload (PARITY — Flutter
     * `AttriaxConfig.sdkMetadata`, types_session_config.dart:108/150). Merged into
     * the app-open `sdk.metadata` block (see [AttriaxRequestBuilders.buildOpen]).
     * `null` omits the block entirely. Flutter defaults this to an empty map and
     * always emits `{clientRuntime:'flutter'}`; the KMP core defaults to `null` and
     * emits nothing extra unless the host supplies metadata (no synthetic
     * clientRuntime key — that stays a per-wrapper concern).
     */
    val sdkMetadata: Map<String, Any?>? = null,
    /**
     * Optional wrapper-supplied device context (PARITY — Flutter `DeviceContextDto`).
     * A host wrapper (Flutter / Unity / React Native) can populate the full device
     * field set here; every supplied value WINS over the core's Android auto-capture
     * (the wrapper knows its runtime best). `null` (the default) leaves the core to
     * auto-capture the non-sensitive device fields on Android and to send only the
     * required five elsewhere. See [AttriaxDeviceContext].
     */
    val deviceContext: AttriaxDeviceContext? = null,
    /**
     * Whether verbose (debug/info) SDK logging is emitted (PARITY — Flutter
     * `AttriaxConfig.enableDebugLogs`, types_session_config.dart:159). Warnings and
     * errors always emit; debug/info are gated behind this flag. Flutter's field is
     * nullable and resolves to `kDebugMode` (on in debug builds, off in release); the
     * KMP core has no build-mode concept, so it defaults to `false`
     * (release-equivalent). Set `true` to surface diagnostics.
     */
    val enableDebugLogs: Boolean = false,
    val requestTimeoutMs: Long = 12_000L,
    val maxQueueSize: Int = 500,
    val eventFlushIntervalMs: Long = 60_000L,
    val flushEventsImmediatelyOnFirstLaunch: Boolean = true,
    val collectAdvertisingId: Boolean = true,
    val automaticCrashReportingEnabled: Boolean = true,
    val gdprEnabled: Boolean = false,
    val anonymousTracking: Boolean = true,
    val sessionTrackingEnabled: Boolean = true,
    val sessionHeartbeatIntervalMs: Long = 5 * 60_000L,
    val firstLaunchSessionHeartbeatIntervalMs: Long = 30_000L,
    /**
     * Whether to capture the Google Play install referrer on first launch and
     * attach it to the app-open (PARITY §3 — app-open enrichment). DEFAULT-ON
     * (a core attribution signal, unlike opt-in attestation). Degrades silently to
     * no-op on non-Play builds / when the Play client is unavailable. Android-only.
     */
    val installReferrerEnabled: Boolean = true,
    val attestationEnabled: Boolean = false,
    /**
     * Optional device-attestation provider (PARITY §9). Ignored unless
     * [attestationEnabled] is `true`. `null` → the shipped
     * [NoopAttestationProvider] (no attestation). Supply
     * [com.attriax.sdk.android.AttriaxPlayIntegrityAttestationProvider] to opt into
     * Play Integrity. Object seam (mirrors the Flutter reference), not a slug string.
     */
    val attestationProvider: AttriaxAttestationProvider? = null,
    val pinnedCertificateSha256Fingerprints: List<String> = emptyList(),
    /**
     * Whether a resolved deep link that carries a backend browser-fallback URL may be
     * opened automatically by the SDK (PARITY — Flutter `AttriaxConfig
     * .automaticBrowserHandling`, types_session_config.dart:119/186-187). DEFAULT-ON,
     * matching Flutter. When `true` and a resolution carries a `browserAction`, the
     * SDK opens the URL via the injected [AttriaxBrowserOpener] (an ACTION_VIEW intent
     * on Android; a documented no-op on jvm/native until desktop browser-open lands).
     */
    val automaticBrowserHandling: Boolean = true,
) {
    init {
        require(maxQueueSize > 0) { "maxQueueSize must be positive" }
    }

    /** The token with surrounding whitespace stripped (Flutter trims the token). */
    val normalizedProjectToken: String get() = projectToken.trim()

    companion object {
        const val DEFAULT_API_BASE_URL: String = "https://api.attriax.com"
    }
}
