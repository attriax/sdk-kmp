package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxSkanCoarseValue
import com.attriax.sdk.AttriaxSkanUpdateResult
import com.attriax.sdk.AttriaxSkanUpdateStatus

/**
 * macOS actuals for the SKAdNetwork seams. SKAdNetwork is an iOS/tvOS StoreKit
 * feature with no macOS counterpart, so SKAN is never supported here → the engine
 * reports a `null` state and returns [AttriaxSkanUpdateStatus.NOT_SUPPORTED] without
 * reaching the update seam (parity with the desktop stub).
 */
internal actual fun attriaxSkanSupported(): Boolean = false

internal actual fun attriaxUpdatePostbackConversionValue(
    fineValue: Int,
    coarseValue: AttriaxSkanCoarseValue,
    lockWindow: Boolean,
): AttriaxSkanUpdateResult = AttriaxSkanUpdateResult(
    status = AttriaxSkanUpdateStatus.NOT_SUPPORTED,
    message = "SKAdNetwork updates are only supported on iOS.",
)
