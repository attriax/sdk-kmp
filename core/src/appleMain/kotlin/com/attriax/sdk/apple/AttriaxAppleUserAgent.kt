package com.attriax.sdk.apple

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSThread
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * Resolves the mandatory, load-bearing SDK User-Agent for the Apple transport
 * (PARITY §8 / row W2).
 *
 * The backend runs `isbot` over the UA and DERIVES the device/OS from it, so iOS/
 * macOS must send a REAL, API-parseable browser UA — the actual `WKWebView`
 * `navigator.userAgent` (real Safari) — never a synthetic `attriax-*-sdk` slug. The
 * UA is ALSO the anonymous-identity key (`sha256(appId, ip, userAgent, dailySalt)`),
 * so it must be STABLE per install and resolved ONCE, before the first request, or a
 * drifting UA fragments anonymous identity.
 *
 * Resolution order (resolved eagerly by the factory, before the transport exists):
 *  1. a wrapper-supplied UA (the Flutter/Unity shim runs its own cached WKWebView
 *     probe and passes the real UA in) — the shipping path,
 *  2. else a ported live `WKWebView` probe (the hack from `AttriaxIosPlugin.swift`):
 *     create a zero-frame `WKWebView` on the MAIN queue and read `navigator.userAgent`
 *     via `evaluateJavaScript`, blocking the (background) caller on a semaphore with a
 *     bounded timeout. Only attempted OFF the main thread — blocking the main thread
 *     would deadlock, so on the main thread we skip straight to (3),
 *  3. else a genuine Safari-shaped fallback built from the OS version
 *     ([attriaxFallbackWebUserAgent], per-platform). This is a real, isbot-passing,
 *     device-derivable Safari UA — NOT the synthetic slug the requirement forbids.
 *
 * The live probe requires a running main run loop (a real app); in a headless
 * context (unit tests) the main queue never drains, the wait times out, and
 * resolution degrades to the Safari-shaped fallback — never throwing, never blocking
 * forever.
 */
object AttriaxAppleUserAgent {

    /** Default bound for the live WKWebView probe (ms). */
    const val DEFAULT_PROBE_TIMEOUT_MS: Long = 1_500L

    /**
     * Resolve the UA to stamp on every request. [suppliedUserAgent] (wrapper path)
     * wins; otherwise the live probe is attempted, then the Safari-shaped fallback.
     */
    fun resolve(
        suppliedUserAgent: String?,
        osVersion: String,
        probeTimeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
    ): String {
        val supplied = suppliedUserAgent?.trim()
        if (!supplied.isNullOrBlank()) return supplied

        val probed = probeWebViewUserAgentBlocking(probeTimeoutMs)?.trim()
        if (!probed.isNullOrBlank()) return probed

        return attriaxFallbackWebUserAgent(osVersion)
    }

    /**
     * Port of the `collectWebViewUserAgent` hack: create a zero-frame [WKWebView] on
     * the main queue and read `navigator.userAgent`, blocking the caller on a
     * semaphore up to [timeoutMs]. Returns null on the main thread (cannot block), on
     * timeout (no running main loop), or on any failure — the caller then falls back.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun probeWebViewUserAgentBlocking(timeoutMs: Long): String? {
        // Blocking the main thread on a main-queue dispatch would deadlock.
        if (NSThread.isMainThread()) return null

        val result = atomic<String?>(null)
        val semaphore = dispatch_semaphore_create(0)

        dispatch_async(dispatch_get_main_queue()) {
            val webView = WKWebView(
                frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                configuration = WKWebViewConfiguration(),
            )
            webView.evaluateJavaScript("navigator.userAgent") { value, _ ->
                (value as? String)?.let { result.value = it }
                // Keep `webView` referenced until the completion runs, then stop it.
                webView.stopLoading()
                dispatch_semaphore_signal(semaphore)
            }
        }

        val timeout = dispatch_time(DISPATCH_TIME_NOW, timeoutMs * 1_000_000L)
        val timedOut = dispatch_semaphore_wait(semaphore, timeout) != 0L
        if (timedOut) return null
        return result.value?.takeIf { it.isNotBlank() }
    }
}

/**
 * A genuine, isbot-passing, device-derivable Safari User-Agent used when neither a
 * wrapper-supplied UA nor a live `WKWebView` probe is available. Per-platform
 * (iPhone/iOS vs Macintosh/macOS shape) so the backend still derives a real device +
 * OS. [osVersion] is the platform OS version string (e.g. "18.5").
 */
internal expect fun attriaxFallbackWebUserAgent(osVersion: String): String
