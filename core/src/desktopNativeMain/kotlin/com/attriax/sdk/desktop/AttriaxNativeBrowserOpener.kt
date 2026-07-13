package com.attriax.sdk.desktop

import com.attriax.sdk.AttriaxBrowserOpener

/**
 * Kotlin/Native desktop [AttriaxBrowserOpener] actual (PARITY §6 — Flutter
 * `AttriaxPlatform.openBrowserUrl`), the native sibling of the JVM
 * [com.attriax.sdk.jvm.AttriaxJvmBrowserOpener] and the Android
 * `AttriaxAndroidBrowserOpener`. Hands the resolved browser-fallback URL to the OS
 * default handler through the per-target [attriaxOpenInBrowser] seam:
 *  - Windows (mingwX64): `ShellExecuteW(NULL, "open", url, …)` (shell32).
 *  - Linux (linuxX64): `xdg-open <url>` via `system` (fails soft if absent).
 *
 * Only `http`/`https` URLs are dispatched. The engine only ever passes web
 * browser-fallback URLs, and the guard is defense-in-depth: it keeps a non-web URL
 * (e.g. a `file:`/scheme that the OS shell would treat as a local command target)
 * from reaching `ShellExecuteW` / the shell. A rejected or failed open returns
 * `false` rather than throwing into the deep-link resolve callback.
 */
class AttriaxNativeBrowserOpener : AttriaxBrowserOpener {

    override fun open(url: String): Boolean {
        if (!isWebUrl(url)) return false
        return try {
            attriaxOpenInBrowser(url)
        } catch (e: Throwable) {
            false
        }
    }

    private fun isWebUrl(url: String): Boolean {
        val lower = url.trimStart().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}

/**
 * Per-target browser open. Dispatches [url] (already scheme-validated as http/https)
 * to the OS default browser. Returns `true` when the open was dispatched.
 */
internal expect fun attriaxOpenInBrowser(url: String): Boolean
