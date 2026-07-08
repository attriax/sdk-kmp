package com.attriax.sdk.internal.installreferrer

import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.attriaxSleep

/**
 * Play install-referrer capture policy (PARITY §3 — app-open enrichment).
 *
 * Mirrors the Flutter reference `AttriaxContextInstallReferrer`:
 *  - **cache-first**: once a non-empty referrer has been resolved it is persisted
 *    and re-attached to every subsequent app-open WITHOUT re-contacting the Play
 *    client (only the raw string is cached — the timestamps are one-shot);
 *  - **fetch-once-with-one-retry**: on the first launch with no cached value, the
 *    provider is called once and, if it yields nothing, retried a single time
 *    after [retryDelayMs] (the Play service is occasionally slow to bind);
 *  - **never persists an empty result**, so a failed capture is retried on the
 *    next launch rather than being latched to "no referrer".
 *
 * Pure and unit-tested: the blocking Play call sits behind
 * [AttriaxInstallReferrerProvider]; the retry pause is injected via [sleeper] so
 * tests run instantly. Runs on the engine's flush executor (never the init
 * thread).
 */
class AttriaxInstallReferrerCoordinator(
    private val provider: AttriaxInstallReferrerProvider,
    private val store: KeyValueStore,
    private val enabled: Boolean,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val sleeper: (Long) -> Unit = { if (it > 0) attriaxSleep(it) },
) {
    /**
     * Whether a blocking Play-client fetch is required this launch — i.e. capture
     * is enabled, a real provider is present, and nothing has been cached yet.
     * When false the caller can stay on the synchronous fast path and rely on
     * [cachedDetails].
     */
    fun needsFetch(): Boolean {
        if (!enabled) return false
        if (provider === AttriaxInstallReferrerProvider.Unavailable) return false
        return storedReferrer() == null
    }

    /**
     * The persisted referrer wrapped for re-attachment (raw string only — the
     * timestamps are not cached), or `null` when nothing has been resolved yet.
     * Synchronous (store read only); never contacts the provider.
     */
    fun cachedDetails(): AttriaxInstallReferrerDetails? {
        if (!enabled) return null
        val cached = storedReferrer() ?: return null
        return AttriaxInstallReferrerDetails(rawReferrer = cached)
    }

    /**
     * Blocking capture: fetch (with one retry) and persist the raw referrer on
     * success, returning the full details (including timestamps) for the app-open.
     * Falls back to [cachedDetails] when a fetch is not actually needed, and
     * returns `null` (persisting nothing) when the provider yields no referrer.
     * Never throws — provider failures degrade to `null`.
     */
    fun fetchAndPersist(): AttriaxInstallReferrerDetails? {
        if (!needsFetch()) return cachedDetails()

        val first = safeFetch()
        val resolved = if (first?.hasReferrer() == true) {
            first
        } else {
            sleeper(retryDelayMs)
            safeFetch()
        }

        val raw = resolved?.rawReferrer?.takeIf { it.isNotEmpty() } ?: return null
        store.putString(KEY_INSTALL_REFERRER, raw)
        return resolved
    }

    private fun safeFetch(): AttriaxInstallReferrerDetails? =
        try {
            provider.fetch()
        } catch (e: Throwable) {
            // A misbehaving Play client must never break the app-open (or init).
            null
        }

    private fun storedReferrer(): String? =
        store.getString(KEY_INSTALL_REFERRER)?.takeIf { it.isNotEmpty() }

    companion object {
        /** Persisted raw Play install-referrer string (cache key). */
        const val KEY_INSTALL_REFERRER: String = "attriax.install_referrer"

        /** Delay before the single capture retry (matches the Flutter reference). */
        const val DEFAULT_RETRY_DELAY_MS: Long = 1_500L
    }
}
