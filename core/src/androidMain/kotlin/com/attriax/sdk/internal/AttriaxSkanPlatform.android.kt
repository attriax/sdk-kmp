package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxSkanCoarseValue
import com.attriax.sdk.AttriaxSkanUpdateResult
import com.attriax.sdk.AttriaxSkanUpdateStatus

// SKAdNetwork is Apple-only; Android has no equivalent framework. Never supported →
// the SKAN engine reports a null state and returns NOT_SUPPORTED without ever
// calling the update seam below.
internal actual fun attriaxSkanSupported(): Boolean = false

internal actual fun attriaxUpdatePostbackConversionValue(
    fineValue: Int,
    coarseValue: AttriaxSkanCoarseValue,
    lockWindow: Boolean,
): AttriaxSkanUpdateResult = AttriaxSkanUpdateResult(
    status = AttriaxSkanUpdateStatus.NOT_SUPPORTED,
    message = "SKAdNetwork updates are only supported on iOS.",
)
