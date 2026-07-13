package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxSkanCoarseValue
import com.attriax.sdk.AttriaxSkanUpdateResult
import com.attriax.sdk.AttriaxSkanUpdateStatus

/**
 * Platform SKAdNetwork support probe.
 *
 * SKAN is an Apple-only StoreKit framework, so every currently-buildable target
 * (android/jvm/native) returns `false` — the SKAN engine then reports a `null`
 * [com.attriax.sdk.AttriaxSkanState] and short-circuits [com.attriax.sdk.AttriaxSkan
 * .updateConversionValue] to [AttriaxSkanUpdateStatus.NOT_SUPPORTED], mirroring the
 * Flutter reference `attriaxPlatformSupportsSkan` gate.
 *
 * iosMain actual: return `true` (SKAdNetwork is available on iOS
 * 14+; older iOS still exposes the register API, and StoreKit itself no-ops safely).
 */
internal expect fun attriaxSkanSupported(): Boolean

/**
 * Platform SKAdNetwork conversion-value update seam.
 *
 * Pushes the resolved [fineValue] (0..63), [coarseValue] and [lockWindow] to Apple.
 * On android/jvm/native SKAN does not exist, so this is a no-op returning a
 * [AttriaxSkanUpdateStatus.NOT_SUPPORTED] result. The engine only calls it when the
 * conversion value actually advances (validation + monotonic gating happen in common
 * — see `AttriaxSkanEngine`).
 *
 * iosMain actual: call
 * `SKAdNetwork.updatePostbackConversionValue(_:coarseValue:lockWindow:completionHandler:)`
 * on iOS 16.1+ (bridging the async completion to a blocking result), falling back to
 * `SKAdNetwork.updateConversionValue(_:)` on older iOS; map success →
 * [AttriaxSkanUpdateStatus.UPDATED] and any framework error → [AttriaxSkanUpdateStatus.ERROR].
 * The first-launch install registration uses `SKAdNetwork.registerAppForAdNetworkAttribution()`
 * / `SKAdNetwork.updatePostbackConversionValue(0, ...)`.
 */
internal expect fun attriaxUpdatePostbackConversionValue(
    fineValue: Int,
    coarseValue: AttriaxSkanCoarseValue,
    lockWindow: Boolean,
): AttriaxSkanUpdateResult
