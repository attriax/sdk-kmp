package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxAttStatus
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppTrackingTransparency.ATTrackingManager
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusAuthorized
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusDenied
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusNotDetermined
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusRestricted
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * Apple actuals for the ATT (App Tracking Transparency) seams (PARITY §5) — the port
 * of the standalone iOS SDK's `AttriaxAppTrackingTransparencyReader`.
 *
 * Reads / prompts via `ATTrackingManager` (AppTrackingTransparency.framework; iOS
 * 14+ / macOS 11+). Reading the status NEVER prompts. `requestTrackingAuthorization`
 * is asynchronous (completion handler); the engine's threading model is synchronous +
 * off-main, so it is bridged to a blocking call on a semaphore with the caller's
 * timeout. Any status Apple does not define maps to [AttriaxAttStatus.UNKNOWN], which
 * the engine OMITS from the app-open.
 */
internal actual fun attriaxAttStatus(): AttriaxAttStatus =
    mapAttStatus(ATTrackingManager.trackingAuthorizationStatus)

@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxRequestAttAuthorization(timeoutMs: Long?): AttriaxAttStatus {
    val resolved = atomic(AttriaxAttStatus.UNKNOWN)
    val semaphore = dispatch_semaphore_create(0)

    ATTrackingManager.requestTrackingAuthorizationWithCompletionHandler { status ->
        resolved.value = mapAttStatus(status)
        dispatch_semaphore_signal(semaphore)
    }

    val deadline = if (timeoutMs != null && timeoutMs > 0L) {
        dispatch_time(DISPATCH_TIME_NOW, timeoutMs * 1_000_000L)
    } else {
        DISPATCH_TIME_FOREVER
    }
    dispatch_semaphore_wait(semaphore, deadline)
    return resolved.value
}

private fun mapAttStatus(status: ULong): AttriaxAttStatus = when (status) {
    ATTrackingManagerAuthorizationStatusAuthorized -> AttriaxAttStatus.AUTHORIZED
    ATTrackingManagerAuthorizationStatusDenied -> AttriaxAttStatus.DENIED
    ATTrackingManagerAuthorizationStatusRestricted -> AttriaxAttStatus.RESTRICTED
    ATTrackingManagerAuthorizationStatusNotDetermined -> AttriaxAttStatus.NOT_DETERMINED
    else -> AttriaxAttStatus.UNKNOWN
}
