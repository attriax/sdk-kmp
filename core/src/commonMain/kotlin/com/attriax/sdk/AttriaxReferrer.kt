package com.attriax.sdk

/**
 * Public referrer-query surface exposed as `attriax.referrer` (referrer
 * query API). Mirrors the Flutter reference `AttriaxReferrer`
 * (`attriax_referrer.dart`): startup attribution snapshots plus runtime deep-link
 * referrers.
 *
 * Unlike the Flutter reference (which returns `Future`s with an optional
 * `timeout`/`safe`), these getters follow the engine's synchronous plain-thread
 * model — the same shape as the `attriax.deepLinks` snapshot accessors. Each
 * resolves to `null` until [Attriax.init] has produced the backing state.
 * [getSessionReferrer] may block on the startup deep-link probe, so it MUST be
 * called off the main thread.
 */
class AttriaxReferrer internal constructor(private val engine: Attriax) {

    /**
     * Original install referrer persisted for this installation, or null.
     *
     * Resolves from local storage on later launches, or after the first successful
     * app-open response on a fresh install/reinstall.
     */
    fun getOriginalInstallReferrer(): AttriaxInstallReferrerDetails? =
        engine.originalInstallReferrer

    /**
     * Reinstall referrer persisted for the current installation, when one exists.
     *
     * Resolves after the first successful app-open response that classifies the
     * launch as a reinstall, or from cached storage on later launches.
     */
    fun getReinstallReferrer(): AttriaxInstallReferrerDetails? =
        engine.reinstallReferrer

    /** Raw Android Play Install Referrer string, when the platform captured one. */
    fun getRawInstallReferrer(): String? =
        engine.rawInstallReferrer

    /**
     * Deep-link referrer that opened the current session (cold-start or deferred),
     * or null when the current session started without one. Settles the startup
     * deep-link probe first — MUST be called off the main thread.
     */
    fun getSessionReferrer(): AttriaxDeepLinkReferrerDetails? =
        engine.sessionReferrer()

    /**
     * Most recent deep-link referrer observed in the current session, or null when
     * no deep link has been handled yet.
     */
    fun getLatestDeepLinkReferrer(): AttriaxDeepLinkReferrerDetails? =
        engine.latestDeepLinkReferrer
}
