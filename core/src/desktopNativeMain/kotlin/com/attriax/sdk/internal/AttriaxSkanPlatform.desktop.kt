package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxSkanCoarseValue
import com.attriax.sdk.AttriaxSkanUpdateResult
import com.attriax.sdk.AttriaxSkanUpdateStatus

// SKAdNetwork is Apple-only. The currently-built native targets (mingwX64/linuxX64)
// have no StoreKit, so SKAN is never supported → the engine reports a null state and
// returns NOT_SUPPORTED without calling the update seam. The future iosMain actual
// replaces both with the real StoreKit SKAdNetwork calls.
internal actual fun attriaxSkanSupported(): Boolean = false

internal actual fun attriaxUpdatePostbackConversionValue(
    fineValue: Int,
    coarseValue: AttriaxSkanCoarseValue,
    lockWindow: Boolean,
): AttriaxSkanUpdateResult = AttriaxSkanUpdateResult(
    status = AttriaxSkanUpdateStatus.NOT_SUPPORTED,
    message = "SKAdNetwork updates are only supported on iOS.",
)
