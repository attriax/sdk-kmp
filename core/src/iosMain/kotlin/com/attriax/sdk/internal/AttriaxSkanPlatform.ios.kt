package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxSkanCoarseValue
import com.attriax.sdk.AttriaxSkanUpdateResult
import com.attriax.sdk.AttriaxSkanUpdateStatus
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import platform.Foundation.NSError
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import platform.StoreKit.SKAdNetwork
import platform.StoreKit.SKAdNetworkCoarseConversionValueHigh
import platform.StoreKit.SKAdNetworkCoarseConversionValueLow
import platform.StoreKit.SKAdNetworkCoarseConversionValueMedium
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * iOS actuals for the SKAdNetwork seams.
 *
 * SKAN is available on iOS, so [attriaxSkanSupported] returns `true`. The
 * conversion-value update is an HONEST StoreKit passthrough — the pure
 * [com.attriax.sdk.internal.skan.AttriaxSkanEngine] has already validated + applied
 * the monotonic rules and only calls this when the value ADVANCES, so here we simply
 * forward the resolved fine/coarse/lock to Apple.
 *
 * Version-gated newest-first: iOS 16.1+ uses
 * `updatePostbackConversionValue(_:coarseValue:lockWindow:completionHandler:)` (fine +
 * coarse tier + lock window, async → bridged to blocking on a semaphore); older iOS
 * falls back to the fine-only `updateConversionValue(_:)`. A StoreKit error maps to
 * [AttriaxSkanUpdateStatus.ERROR]; success maps to [AttriaxSkanUpdateStatus.UPDATED].
 */
internal actual fun attriaxSkanSupported(): Boolean = true

@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxUpdatePostbackConversionValue(
    fineValue: Int,
    coarseValue: AttriaxSkanCoarseValue,
    lockWindow: Boolean,
): AttriaxSkanUpdateResult {
    val clamped = fineValue.coerceIn(0, 63)
    return try {
        if (isOperatingSystemAtLeast(major = 16, minor = 1)) {
            val error = atomic<NSError?>(null)
            val semaphore = dispatch_semaphore_create(0)
            SKAdNetwork.updatePostbackConversionValue(
                fineValue = clamped.toLong(),
                coarseValue = mapCoarse(coarseValue),
                lockWindow = lockWindow,
            ) { err ->
                error.value = err
                dispatch_semaphore_signal(semaphore)
            }
            dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)
            val failure = error.value
            if (failure != null) {
                skanError(failure.localizedDescription)
            } else {
                skanUpdated(clamped, coarseValue, lockWindow)
            }
        } else {
            // Pre-16.1: fine-value-only conversion update (no coarse/lock, no completion).
            SKAdNetwork.updateConversionValue(clamped.toLong())
            skanUpdated(clamped, coarseValue, lockWindow)
        }
    } catch (e: Throwable) {
        skanError(e.message)
    }
}

private fun skanUpdated(
    fineValue: Int,
    coarseValue: AttriaxSkanCoarseValue,
    lockWindow: Boolean,
): AttriaxSkanUpdateResult = AttriaxSkanUpdateResult(
    status = AttriaxSkanUpdateStatus.UPDATED,
    fineValue = fineValue,
    coarseValue = coarseValue,
    lockWindow = lockWindow,
)

private fun skanError(message: String?): AttriaxSkanUpdateResult = AttriaxSkanUpdateResult(
    status = AttriaxSkanUpdateStatus.ERROR,
    message = message ?: "SKAdNetwork conversion-value update failed.",
)

private fun mapCoarse(value: AttriaxSkanCoarseValue): String? = when (value) {
    AttriaxSkanCoarseValue.LOW -> SKAdNetworkCoarseConversionValueLow
    AttriaxSkanCoarseValue.MEDIUM -> SKAdNetworkCoarseConversionValueMedium
    AttriaxSkanCoarseValue.HIGH -> SKAdNetworkCoarseConversionValueHigh
}

@OptIn(ExperimentalForeignApi::class)
private fun isOperatingSystemAtLeast(major: Int, minor: Int): Boolean {
    val version = cValue<NSOperatingSystemVersion> {
        majorVersion = major.toLong()
        minorVersion = minor.toLong()
        patchVersion = 0
    }
    return NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(version)
}
