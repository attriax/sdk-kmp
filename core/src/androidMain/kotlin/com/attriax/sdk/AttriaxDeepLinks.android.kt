package com.attriax.sdk

import android.content.Intent
import com.attriax.sdk.android.AttriaxDeepLinkIntents

/**
 * Android `Intent`-based deep-link capture as extensions on
 * the platform-agnostic [AttriaxDeepLinks] facade. A native SDK cannot subscribe
 * to a platform EventChannel like the Flutter plugin does; instead the host app
 * forwards its Activity intents via these helpers, which lower to the common
 * [AttriaxDeepLinks.handleUri] / [AttriaxDeepLinks.completeInitialLinkIfAbsent].
 */

/**
 * Feed an incoming deep-link [intent] (e.g. from `Activity.onNewIntent`). The URI
 * is extracted from the intent's `ACTION_VIEW` data; non-link intents are ignored.
 * Resolution happens asynchronously; observe [AttriaxDeepLinks.addListener] for the
 * resolved event.
 */
fun AttriaxDeepLinks.handleIntent(intent: Intent?) {
    val link = AttriaxDeepLinkIntents.extractLink(intent) ?: return
    handleUri(link, isInitialLink = false)
}

/**
 * Feed the LAUNCH deep-link [intent] (e.g. from `Activity.onCreate`), treated as
 * the initial link. Use [handleIntent] for subsequent links. Call this even when
 * the intent is not a link so the initial-link probe can complete.
 */
fun AttriaxDeepLinks.handleLaunchIntent(intent: Intent?) {
    val link = AttriaxDeepLinkIntents.extractLink(intent)
    if (link == null) {
        completeInitialLinkIfAbsent()
        return
    }
    handleUri(link, isInitialLink = true)
}
