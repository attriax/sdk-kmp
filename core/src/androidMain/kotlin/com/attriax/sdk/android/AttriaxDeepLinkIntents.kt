package com.attriax.sdk.android

import android.content.Intent

/**
 * Thin Android adapter that pulls the deep-link URI out of an
 * [android.content.Intent]. This is the ONLY deep-link file
 * that touches an Android framework type, keeping the core (`internal/deeplink`)
 * JVM-testable.
 *
 * A native SDK cannot subscribe to a platform EventChannel like the Flutter
 * plugin does; instead the host app forwards its Activity intents to the SDK. The
 * typical wiring is:
 *
 * ```kotlin
 * // In Activity.onCreate:
 * attriax.deepLinks.handleIntent(intent, isLaunchIntent = true)
 * // In Activity.onNewIntent:
 * attriax.deepLinks.handleIntent(intent)
 * ```
 */
object AttriaxDeepLinkIntents {

    /**
     * Extract the deep-link URI string from [intent], or null when the intent has
     * no `ACTION_VIEW` data. Only `ACTION_VIEW` intents carry a deep link; other
     * actions (e.g. `ACTION_MAIN` launcher taps) are ignored so a plain launch
     * does not look like a link.
     */
    fun extractLink(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        val raw = data.toString().trim()
        return raw.ifEmpty { null }
    }
}
