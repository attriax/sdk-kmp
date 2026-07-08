package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxAttStatus

// ATT is Apple-only; the JVM has no tracking-authorization framework. Always
// UNKNOWN → the engine omits `attStatus` unless a wrapper supplies a real one.
internal actual fun attriaxAttStatus(): AttriaxAttStatus = AttriaxAttStatus.UNKNOWN

internal actual fun attriaxRequestAttAuthorization(timeoutMs: Long?): AttriaxAttStatus =
    AttriaxAttStatus.UNKNOWN
