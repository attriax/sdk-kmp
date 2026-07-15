package com.attriax.sdk.internal

/**
 * Persists the device identity (Flutter `attriax_preferences_store.dart:231-232`).
 *
 * The store holds THREE keys, each named for exactly what it contains:
 *
 * - [KEY_DEVICE_ID_FALLBACK] — the generated fallback seed (16 secure-random bytes),
 *   minted ONCE on first launch and stable thereafter. This is only the *reported*
 *   identity on platforms with no native id (source `persistent_storage`); on
 *   Android-with-SSAID or Apple-with-IDFV it is dormant — written, never sent.
 * - [KEY_DEVICE_ID_RESOLVED] — the identity actually reported to the backend this
 *   launch (the raw SSAID / IDFV / GAID, or the fallback seed when nothing native
 *   resolves).
 * - [KEY_DEVICE_ID_SOURCE] — the source slug of [KEY_DEVICE_ID_RESOLVED], the key it
 *   sits next to (e.g. `android_ssaid`).
 *
 * The resolved pair is re-written every launch; the fallback seed is not. Anyone
 * reading these prefs cold (debug tooling, a wrapper, a support script) can now
 * correlate [KEY_DEVICE_ID_RESOLVED] against the dashboard directly.
 *
 * MIGRATION: the fallback seed previously lived under [LEGACY_KEY_DEVICE_ID]
 * (`attriax.device_id`) — a name that read like "the device id" while holding a value
 * that, on Android, was never sent. On platforms with no native id that seed IS the
 * identity, so [loadOrCreate] carries a legacy-only value forward to the new key
 * before minting anything. Without that carry-forward every existing install on a
 * fallback platform would silently become a brand-new device on upgrade.
 *
 * All keys (including the legacy one) are cleared by [clear] (invoked by `reset()`).
 */
class AttriaxDeviceIdentityStore(
    private val store: KeyValueStore,
    private val resolver: AttriaxDeviceIdentityResolver,
) {
    fun loadOrCreate(): ResolvedDeviceId {
        val fallbackId = loadOrCreateFallbackId()
        val resolved = resolver.resolve(fallbackId)
        // Persist the resolved identity + its source so they survive restarts and are
        // observable; they are re-resolved (and may legitimately change) each launch.
        store.putString(KEY_DEVICE_ID_RESOLVED, resolved.value)
        store.putString(KEY_DEVICE_ID_SOURCE, resolved.source)
        return resolved
    }

    /**
     * Returns the stable fallback seed, in precedence order:
     *   1. the new key, when already present (fresh install past its first launch, or
     *      an install that has already migrated);
     *   2. the legacy key, migrated forward (upgrade path — this value may BE the
     *      device's reported identity, so it must never be discarded);
     *   3. a freshly generated id (true first launch).
     *
     * The legacy key is removed only AFTER the value is durably under the new key, so
     * an interrupted migration degrades to "both present", which case 1 handles.
     */
    private fun loadOrCreateFallbackId(): String {
        store.getString(KEY_DEVICE_ID_FALLBACK)?.let { return it }

        store.getString(LEGACY_KEY_DEVICE_ID)?.let { legacyId ->
            store.putString(KEY_DEVICE_ID_FALLBACK, legacyId)
            store.remove(LEGACY_KEY_DEVICE_ID)
            return legacyId
        }

        return AttriaxIdGenerator.generate().also {
            store.putString(KEY_DEVICE_ID_FALLBACK, it)
        }
    }

    fun clear() {
        store.remove(KEY_DEVICE_ID_FALLBACK)
        store.remove(KEY_DEVICE_ID_RESOLVED)
        store.remove(KEY_DEVICE_ID_SOURCE)
        // A store that never went through loadOrCreate() may still hold the legacy key;
        // leaving it would let migration resurrect a supposedly-reset identity.
        store.remove(LEGACY_KEY_DEVICE_ID)
    }

    companion object {
        const val KEY_DEVICE_ID_FALLBACK = "attriax.device_id_fallback"
        const val KEY_DEVICE_ID_RESOLVED = "attriax.device_id_resolved"
        const val KEY_DEVICE_ID_SOURCE = "attriax.device_id_source"

        /** Pre-rename name of [KEY_DEVICE_ID_FALLBACK]; read once, then migrated away. */
        const val LEGACY_KEY_DEVICE_ID = "attriax.device_id"
    }
}
