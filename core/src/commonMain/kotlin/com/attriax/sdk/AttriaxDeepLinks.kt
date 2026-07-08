package com.attriax.sdk

/**
 * Public deep-link surface exposed as `attriax.deepLinks` (PARITY §6, rows DL1–DL4).
 * Mirrors the Flutter reference `AttriaxDeepLinks` (`attriax_deep_links.dart`),
 * adapted to a native library: instead of a platform EventChannel, the host app
 * forwards its Activity intents.
 *
 * The Android `Intent`-taking helpers (`handleIntent` / `handleLaunchIntent`) live
 * in an androidMain extension file so this facade stays platform-agnostic; both
 * lower to [handleUri] / [completeInitialLinkIfAbsent] here.
 *
 * Observers use a simple listener pattern ([addListener]/[removeListener]) — no
 * coroutines, matching the engine's plain-thread model.
 */
class AttriaxDeepLinks internal constructor(private val engine: Attriax) {

    // -------- native capture (row DL1) --------

    /**
     * Feed a raw deep-link URI string directly (for hosts that resolve the URI
     * themselves). [isInitialLink] marks the launch link.
     */
    fun handleUri(rawUri: String, isInitialLink: Boolean = false) {
        engine.handleIncomingDeepLink(rawUri, isInitialLink = isInitialLink)
    }

    /**
     * Mark the initial-link probe complete when the launch carried no deep link, so
     * a [waitForInitialDeepLink] caller unblocks. Used by the androidMain
     * `handleLaunchIntent` extension when the launch intent is not a link.
     */
    internal fun completeInitialLinkIfAbsent() {
        engine.completeInitialDeepLinkIfAbsent()
    }

    // -------- observers --------

    /** Broadcast handled deep-link events (resolved incoming + deferred matches). */
    fun addListener(listener: AttriaxDeepLinkListener) = engine.addDeepLinkListener(listener)
    fun removeListener(listener: AttriaxDeepLinkListener) = engine.removeDeepLinkListener(listener)

    /** Broadcast raw (pre-resolution) deep-link inputs from native capture. */
    fun addRawListener(listener: AttriaxRawDeepLinkListener) = engine.addRawDeepLinkListener(listener)
    fun removeRawListener(listener: AttriaxRawDeepLinkListener) = engine.removeRawDeepLinkListener(listener)

    // -------- state --------

    /** Launch raw deep-link event captured during startup, when one was present. */
    val rawInitialDeepLink: AttriaxRawDeepLinkEvent? get() = engine.rawInitialDeepLink

    /** Most recent handled deep-link event seen by the SDK. */
    val latestDeepLink: AttriaxDeepLinkEvent? get() = engine.latestDeepLink

    /**
     * Launch deep-link event captured during startup, when one was present. Stays
     * null until the initial-link probe completes; use [initialDeepLinkResolved] to
     * distinguish "not resolved yet" from "resolved and none found".
     */
    val initialDeepLink: AttriaxDeepLinkEvent? get() = engine.initialDeepLink

    /** Whether the initial-link probe has completed for this app session. */
    val initialDeepLinkResolved: Boolean get() = engine.isInitialDeepLinkResolved

    /**
     * Block until the initial-link probe finishes, returning the launch deep-link
     * event (or null when none was present). MUST be called off the main thread.
     */
    fun waitForInitialDeepLink(): AttriaxDeepLinkEvent? = engine.waitForInitialDeepLink()

    // -------- manual / dynamic links --------

    /**
     * Record a deep link manually (public `recordDeepLink`). Use when your router
     * receives a URI before the SDK captures it. [metadata] is sent with the
     * resolution request; the resolved event is emitted to observers.
     */
    fun recordDeepLink(
        uri: String,
        metadata: Map<String, Any?>? = null,
        source: String = "manual",
    ) = engine.recordDeepLink(uri, metadata, source)

    /**
     * Create a short dynamic link (public `createDynamicLink`). Attriax generates
     * the short code server-side and returns the shareable URL + persisted record.
     * Performs blocking I/O — call off the main thread.
     */
    fun createDynamicLink(
        name: String? = null,
        destinationUrl: String? = null,
        group: String? = null,
        prefix: String? = null,
        socialPreview: AttriaxDynamicLinkSocialPreview? = null,
        utms: AttriaxDynamicLinkUtms? = null,
        redirects: AttriaxDynamicLinkRedirects? = null,
        data: Map<String, Any?>? = null,
    ): AttriaxCreateDynamicLinkResult = engine.createDynamicLink(
        name = name,
        destinationUrl = destinationUrl,
        group = group,
        prefix = prefix,
        socialPreview = socialPreview,
        utms = utms,
        redirects = redirects,
        data = data,
    )
}
