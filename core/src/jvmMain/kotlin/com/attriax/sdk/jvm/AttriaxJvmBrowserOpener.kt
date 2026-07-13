package com.attriax.sdk.jvm

import com.attriax.sdk.AttriaxBrowserOpener
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.net.URI

/**
 * JVM-desktop [AttriaxBrowserOpener] actual (PARITY §6 — Flutter
 * `AttriaxPlatform.openBrowserUrl`; the Android sibling is
 * [com.attriax.sdk.android.AttriaxAndroidBrowserOpener]). Opens the resolved
 * browser-fallback URL through [java.awt.Desktop.browse].
 *
 * Fails soft — returns `false` rather than throwing — whenever the environment
 * cannot open a browser: a headless JVM, a platform without AWT `Desktop` support,
 * or one where the `BROWSE` action is unavailable. `browse` itself is guarded so a
 * malformed URL or a launch failure never propagates into the deep-link resolve
 * callback.
 */
class AttriaxJvmBrowserOpener : AttriaxBrowserOpener {

    override fun open(url: String): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        if (!Desktop.isDesktopSupported()) return false
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
        return try {
            desktop.browse(URI(url))
            true
        } catch (e: Exception) {
            false
        }
    }
}
