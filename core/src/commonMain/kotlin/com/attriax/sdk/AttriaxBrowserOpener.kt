package com.attriax.sdk

/**
 * Platform seam for opening a resolved deep-link browser-fallback URL (
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
         * Default seam that opens nothing and reports `false` — used by the pure
         * engine (and any host that does not wire a real opener). Every shipping
         * target now has a real opener: Android injects an ACTION_VIEW-backed opener,
         * JVM uses [com.attriax.sdk.jvm.AttriaxJvmBrowserOpener]
         * (`java.awt.Desktop.browse`), and desktop-native uses
         * [com.attriax.sdk.desktop.AttriaxNativeBrowserOpener] (`ShellExecuteW` on
         * Windows, `xdg-open` on Linux).
         */
        val Unavailable: AttriaxBrowserOpener = AttriaxBrowserOpener { false }
    }
}
