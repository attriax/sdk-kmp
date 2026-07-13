package com.attriax.sdk.internal.referrer

import com.attriax.sdk.AttriaxDeepLinkEvent
import com.attriax.sdk.AttriaxDeepLinkListener
import com.attriax.sdk.AttriaxDeepLinkReferrerDetails
import com.attriax.sdk.AttriaxInstallReferrerDetails
import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.deeplink.AttriaxDeepLinkManager
import com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerCoordinator
import com.attriax.sdk.internal.json.Json
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Backs the public `attriax.referrer` query API.
 * Mirrors the Flutter reference `AttriaxReferrerManager`
 * (`internal/attriax_referrer_manager.dart`), adapted to the engine's plain-thread
 * / listener model (no completers/streams).
 *
 * Sources, per getter (matching the Flutter reference):
 *  - **raw install referrer** — the raw Play referrer string persisted by
 *    [AttriaxInstallReferrerCoordinator] (Flutter reads it from the platform install
 *    referrer manager, which persists the same value). Trimmed, empty → null.
 *  - **original / reinstall install referrer** — the attribution records the
 *    app-open RESPONSE returns, persisted here from [handleAppOpenResponse]
 *    (Flutter `_observeInstallReferrers` persists `result.originalInstallReferrer` /
 *    `result.reinstallReferrer`). Survives restarts via the store.
 *  - **session referrer** — the first session-opening (cold-start or deferred)
 *    deep-link referrer of the current session, captured from the deep-link
 *    observer stream (Flutter `_handleDeepLinkEvent` +
 *    `attriaxIsSessionOpeningDeepLinkEvent`).
 *  - **latest deep-link referrer** — the most recent handled deep-link referrer,
 *    captured from the same observer stream.
 */
internal class AttriaxReferrerCoordinator(
    private val store: KeyValueStore,
    private val deepLinkManager: AttriaxDeepLinkManager,
) : AttriaxDeepLinkListener {

    private val lock = SynchronizedObject()

    @Volatile private var latestReferrer: AttriaxDeepLinkReferrerDetails? = null
    @Volatile private var sessionReferrerValue: AttriaxDeepLinkReferrerDetails? = null
    @Volatile private var sessionReferrerResolved: Boolean = false

    /**
     * Subscribe to the deep-link observer stream. Registered before the app-open
     * fires so a deferred deep-link referrer (recovered from the app-open response)
     * is captured live; a late registration still receives the replayed latest event
     * via [AttriaxDeepLinkManager.addListener].
     */
    fun attach() {
        deepLinkManager.addListener(this)
    }

    override fun onDeepLink(event: AttriaxDeepLinkEvent) {
        val details = AttriaxReferrerMapper.deepLinkReferrerFromEvent(event)
        synchronized(lock) {
            latestReferrer = details
            // The FIRST session-opening (cold-start OR deferred) event of the session
            // becomes the session referrer and is not overwritten by later links.
            if (!sessionReferrerResolved && (event.isColdStart || event.isDeferred)) {
                sessionReferrerValue = details
                sessionReferrerResolved = true
            }
        }
    }

    /** Most recent handled deep-link referrer, or null when none has been observed. */
    val latestDeepLinkReferrer: AttriaxDeepLinkReferrerDetails?
        get() = latestReferrer

    /**
     * The deep-link referrer that opened the current session, or null when the
     * session started without one. Settles the startup deep-link probe first (like
     * the Flutter reference waits for the initial-link flow); MUST be called off the
     * main thread because it may block on that probe.
     */
    fun sessionReferrer(): AttriaxDeepLinkReferrerDetails? {
        if (sessionReferrerResolved) return sessionReferrerValue
        // Let a cold-start launch link resolve; deferred links are captured live via
        // the app-open recovery emit. Returns immediately once the probe is resolved.
        deepLinkManager.waitForInitialDeepLink()
        return sessionReferrerValue
    }

    /** The original install-attribution record persisted from the app-open response. */
    fun originalInstallReferrer(): AttriaxInstallReferrerDetails? =
        readDetails(KEY_ORIGINAL_INSTALL_REFERRER_DETAILS)

    /** The reinstall-attribution record persisted from the app-open response. */
    fun reinstallReferrer(): AttriaxInstallReferrerDetails? =
        readDetails(KEY_REINSTALL_REFERRER_DETAILS)

    /** The raw Play install-referrer string, trimmed; empty/absent → null. */
    fun rawInstallReferrer(): String? =
        store.getString(AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Persist the `originalInstallReferrer` / `reinstallReferrer` attribution objects
     * carried by the app-open RESPONSE (Flutter `_observeInstallReferrers`). Called
     * from the engine's KIND_OPEN delivery hook. Best-effort — never throws.
     */
    fun handleAppOpenResponse(data: Map<String, Any?>?) {
        if (data == null) return
        (data["originalInstallReferrer"] as? Map<*, *>)?.let {
            store.putString(KEY_ORIGINAL_INSTALL_REFERRER_DETAILS, Json.encode(it))
        }
        (data["reinstallReferrer"] as? Map<*, *>)?.let {
            store.putString(KEY_REINSTALL_REFERRER_DETAILS, Json.encode(it))
        }
    }

    /**
     * Wipe stored install-attribution referrer state when attribution consent is
     * revoked (privacy parity with the Flutter reference
     * `AttriaxReferrerManager.prepareForDeniedAttributionState()`, called from
     * `attriax_runtime.dart:976` when the active runtime state resolves with
     * attribution denied).
     *
     * Mirrors Flutter's clearing scope exactly: removes the persisted
     * original/reinstall attribution records AND the raw Play install-referrer
     * string (Flutter's `clearStoredReferrer`), so no install/reinstall attribution
     * remains readable through the query API. The session / latest deep-link
     * referrers are intentionally left intact — Flutter does not clear them here
     * (they are deep-link, not install-attribution, and are session-scoped).
     * Best-effort; never throws.
     */
    fun prepareForDeniedAttributionState() {
        store.remove(KEY_ORIGINAL_INSTALL_REFERRER_DETAILS)
        store.remove(KEY_REINSTALL_REFERRER_DETAILS)
        store.remove(AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER)
    }

    private fun readDetails(key: String): AttriaxInstallReferrerDetails? {
        val raw = store.getString(key) ?: return null
        val map = try {
            Json.decode(raw) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: return null
        return AttriaxReferrerMapper.installReferrerDetailsFromMap(map)
    }

    companion object {
        /** Persisted JSON of the original-install attribution record. */
        const val KEY_ORIGINAL_INSTALL_REFERRER_DETAILS: String =
            "attriax.install_referrer_details"

        /** Persisted JSON of the reinstall attribution record. */
        const val KEY_REINSTALL_REFERRER_DETAILS: String =
            "attriax.reinstall_referrer_details"
    }
}
