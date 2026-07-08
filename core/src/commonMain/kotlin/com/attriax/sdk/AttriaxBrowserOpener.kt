package com.attriax.sdk

/**
 * Platform seam for opening a resolved deep-link browser-fallback URL (PARITY §6 —
 * Flutter `AttriaxPlatform.openBrowserUrl`, invoked by
 * `attriax_deep_link_browser_handler.dart`). Injected like the other android
 * adapters (scheduler / install-referrer / lifecycle binder) rather than an
 * expect/actual, because Android needs a `Context` that is not reachable from
 * `commonMain`.
 *
 * The engine only calls [open] when [AttriaxConfig.automaticBrowserHandling] is on
 * AND the resolution actually carries a browser action, mirroring Flutter's guard.
 */
fun interface AttriaxBrowserOpener {
    /** Open [url] in a browser. Returns `true` when the open was dispatched. */
    fun open(url: String): Boolean

    companion object {
        /**
         * Default seam that opens nothing and reports `false` (used by the pure
         * engine + jvm/native, where desktop browser-open is not yet implemented).
         * The android factory injects a real ACTION_VIEW-backed opener.
         */
        val Unavailable: AttriaxBrowserOpener = AttriaxBrowserOpener { false }
    }
}
