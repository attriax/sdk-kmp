package com.attriax.sdk.internal

/**
 * Platform Apple Search Ads (AdServices) attribution-token seam (Epic 8.5).
 *
 * Fetches the opaque AdServices attribution token that is later POSTed to
 * `POST /api/sdk/v1/asa/token`. ASA / AdServices is Apple-only, so every
 * currently-buildable target (android/jvm/native) returns `null` → the ASA token
 * manager makes no submission. Blocking (NO coroutines in the core); the caller
 * invokes it off the main thread.
 *
 * iosMain actual (deferred to Mac): call `AAAttribution.attributionToken()` and
 * return the token string; return `null` on any error (the framework throws when
 * AdServices is unavailable, e.g. Simulator / pre-iOS-14.3), so capture stays
 * best-effort and never breaks init.
 */
internal expect fun attriaxFetchAsaAttributionToken(): String?
