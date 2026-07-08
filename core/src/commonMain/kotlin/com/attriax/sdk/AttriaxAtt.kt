package com.attriax.sdk

/**
 * Apple App Tracking Transparency (ATT) authorization status (Epic 8.5, PARITY §5
 * — the `consent.att` sub-surface).
 *
 * The [wireValue]s match the api `SdkV1OpenDto.attStatus` contract EXACTLY
 * (`authorized|denied|restricted|notDetermined|unknown`); the engine emits the
 * resolved value TOP-LEVEL on the app-open request (mirroring `attestation`), and
 * OMITS it when [UNKNOWN] — see [Attriax.resolveAttStatusWire]. ATT only exists on
 * Apple platforms; on Android/jvm/native the platform seam always reports [UNKNOWN]
 * (→ omitted) unless a wrapper supplies a real status via
 * [AttriaxConfig.attStatus] or [AttriaxAttConsent] runtime setter.
 *
 * The Flutter reference collapses several extra SDK statuses (`notSupported`,
 * `disabled`, `timedOut`) that this core does not model: `notSupported`/`disabled`
 * and a `null` input map to OMIT, `timedOut` maps to the `unknown` wire value.
 * The KMP core's [UNKNOWN] covers the non-Apple / unresolved case and OMITS, which
 * matches Flutter's `notSupported`/`disabled` → omit for the platforms this core
 * builds today.
 */
enum class AttriaxAttStatus(val wireValue: String) {
    AUTHORIZED("authorized"),
    DENIED("denied"),
    RESTRICTED("restricted"),
    NOT_DETERMINED("notDetermined"),
    UNKNOWN("unknown"),
}
