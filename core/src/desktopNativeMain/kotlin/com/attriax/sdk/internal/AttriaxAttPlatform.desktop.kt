package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxAttStatus

// ATT is Apple-only. The currently-built native targets (mingwX64/linuxX64) have
// no tracking-authorization framework, so this is always UNKNOWN → the engine
// omits `attStatus` unless a wrapper supplies a real one. The future iosMain
// actual (deferred to Mac) replaces this with the real ATTrackingManager calls.
internal actual fun attriaxAttStatus(): AttriaxAttStatus = AttriaxAttStatus.UNKNOWN

internal actual fun attriaxRequestAttAuthorization(timeoutMs: Long?): AttriaxAttStatus =
    AttriaxAttStatus.UNKNOWN
