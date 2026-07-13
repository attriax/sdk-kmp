package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxAttStatus

/**
 * Platform ATT (App Tracking Transparency) status seam.
 *
 * Reads the current Apple ATT authorization status WITHOUT prompting. ATT is an
 * Apple-only framework, so every currently-buildable target (android/jvm/native)
 * returns [AttriaxAttStatus.UNKNOWN] — the engine then OMITS `attStatus` from the
 * app-open unless a wrapper supplied a real status.
 *
 * iosMain actual: map `ATTrackingManager.trackingAuthorizationStatus`
 * (authorized/denied/restricted/notDetermined) → [AttriaxAttStatus]; any other →
 * UNKNOWN.
 */
internal expect fun attriaxAttStatus(): AttriaxAttStatus

/**
 * Platform ATT authorization-request seam.
 *
 * Prompts the user for tracking authorization (on Apple) and returns the resulting
 * status. Blocking with an optional [timeoutMs] (NO coroutines in the core — this
 * matches the engine's off-main-thread threading model); the caller invokes it off
 * the main thread. On android/jvm/native ATT does not exist, so this is a no-op
 * that returns [AttriaxAttStatus.UNKNOWN] immediately.
 *
 * iosMain actual: call
 * `ATTrackingManager.requestTrackingAuthorization(completionHandler:)`, bridge the
 * async completion back to a blocking result honoring [timeoutMs], and map the
 * resolved status → [AttriaxAttStatus].
 */
internal expect fun attriaxRequestAttAuthorization(timeoutMs: Long?): AttriaxAttStatus
