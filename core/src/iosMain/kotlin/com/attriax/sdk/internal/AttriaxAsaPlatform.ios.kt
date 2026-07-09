package com.attriax.sdk.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AdServices.AAAttribution
import platform.Foundation.NSError

/**
 * iOS actual for the Apple Search Ads (AdServices) attribution-token seam (Epic 8.5)
 * — the port of the standalone iOS SDK's `AttriaxAsaTokenCapture.fetchAttributionToken`.
 *
 * Reads `AAAttribution.attributionToken()` (AdServices.framework; iOS 14.3+) and
 * returns the opaque token; the token is forwarded verbatim (the server exchanges it
 * with Apple). Any error — token unavailable, called too early, unsupported OS /
 * Simulator — degrades to `null` so capture stays best-effort and NEVER breaks init.
 * The ASA token manager makes no submission when this returns `null`.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxFetchAsaAttributionToken(): String? = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    val token = try {
        AAAttribution.attributionTokenWithError(error.ptr)
    } catch (e: Throwable) {
        null
    }
    if (error.value != null) null else token
}
