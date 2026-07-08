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
    /**
     * Wrapper-supplied Apple ATT (App Tracking Transparency) status (Epic 8.5,
     * PARITY §5 — `consent.att`). ATT is an Apple-only framework the KMP core
     * cannot query off-iOS, so a host wrapper (Flutter / Unity / React Native iOS
     * plugin) that already obtained the status via `ATTrackingManager` supplies it
     * here (or at runtime via [AttriaxAttConsent] / [com.attriax.sdk.Attriax]).
     * `null` (the default) → the engine falls back to the platform ATT seam, which
     * reports [AttriaxAttStatus.UNKNOWN] on every currently-built target and is
     * therefore OMITTED from the app-open. A wrapper-supplied non-UNKNOWN status is
     * emitted TOP-LEVEL as `attStatus` (mirrors `attestation`).
     */
    val attStatus: AttriaxAttStatus? = null,
    /**
     * Whether the SDK requests Apple ATT authorization on init (PARITY — Flutter
     * `AttriaxConfig.requestTrackingAuthorizationOnInit`,
     * types_session_config.dart:117/181). DEFAULT-OFF, matching Flutter. When
     * `true`, [com.attriax.sdk.Attriax.init] invokes the ATT request seam (a no-op
     * returning [AttriaxAttStatus.UNKNOWN] on every currently-built target; the
     * future iosMain actual prompts via `ATTrackingManager`).
     */
    val requestTrackingAuthorizationOnInit: Boolean = false,
    /**
     * Timeout (ms) for resolving the Apple ATT authorization status on init
     * (PARITY — Flutter `AttriaxConfig.trackingAuthorizationStatusTimeout`,
     * types_session_config.dart:118/184, a `Duration` defaulting to 60s). Expressed
     * in milliseconds here to keep [AttriaxConfig] time-library-free; default
     * `60_000` matches Flutter's 60-second default. Passed to the ATT request seam.
     */
    val trackingAuthorizationStatusTimeoutMs: Long = 60_000L,
    /**
     * SKAdNetwork (SKAN) configuration (Epic 8.5, PARITY — Flutter `AttriaxConfig.skan`,
     * a nullable `AttriaxSkanConfig`). `null` (the default) resolves to a default
     * [AttriaxSkanConfig] (`enabled = true`), matching Flutter's
     * `_config.skan ?? const AttriaxSkanConfig()`. `enabled = false` makes
     * `attriax.skan.updateConversionValue` return
     * [AttriaxSkanUpdateStatus.DISABLED]. SKAN is Apple-only, so off-iOS this is inert
     * regardless (the platform seam reports SKAN unsupported → NOT_SUPPORTED).
     */
    val skan: AttriaxSkanConfig? = null,
    /**
     * Whether the SDK auto-captures the Apple Search Ads (AdServices) attribution token
     * on init and POSTs it to `/api/sdk/v1/asa/token` (Epic 8.5). DEFAULT-ON.
     *
     * NOTE (default difference): the Flutter reference has NO dedicated ASA flag — it
     * always attempts capture on init on iOS, gated only by attribution consent. The
     * KMP core adds this explicit flag (default `true` = equivalent always-on behavior)
     * so a host can opt out; the auto-capture is additionally gated by attribution
     * consent, matching Flutter. The AdServices token is Apple-only, so off-iOS the
     * fetch seam returns `null` and nothing is sent regardless of this flag. A wrapper
     * can always submit a natively-fetched token via
     * [com.attriax.sdk.Attriax.submitAsaToken] irrespective of this flag.
     */
    val asaTokenCaptureEnabled: Boolean = true,
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
