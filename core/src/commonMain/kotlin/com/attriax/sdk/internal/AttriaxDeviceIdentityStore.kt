package com.attriax.sdk.internal

/**
 * Persists the resolved device identity (PARITY §2, rows D1/D2/D4;
 * Flutter `attriax_preferences_store.dart:231-232`).
 *
 * Keys `attriax.device_id` / `attriax.device_id_source`. The fallback id is
 * generated ONCE (16 secure-random bytes) and reused; both keys are cleared on
 * [clear] (invoked by `reset()`).
 *
 * `loadOrCreate` resolves the preferred native source each launch (SSAID → GAID →
 * persistent-storage fallback) but the generated fallback id itself is stable.
 */
class AttriaxDeviceIdentityStore(
    private val store: KeyValueStore,
    private val resolver: AttriaxDeviceIdentityResolver,
) {
    fun loadOrCreate(): ResolvedDeviceId {
        val fallbackId = store.getString(KEY_DEVICE_ID) ?: AttriaxIdGenerator.generate().also {
            store.putString(KEY_DEVICE_ID, it)
        }
        val resolved = resolver.resolve(fallbackId)
        // Persist the currently-resolved source so it survives restarts and is
        // observable; the fallback id is already persisted above.
        store.putString(KEY_DEVICE_ID_SOURCE, resolved.source)
        return resolved
    }

    fun clear() {
        store.remove(KEY_DEVICE_ID)
        store.remove(KEY_DEVICE_ID_SOURCE)
    }

    companion object {
        const val KEY_DEVICE_ID = "attriax.device_id"
        const val KEY_DEVICE_ID_SOURCE = "attriax.device_id_source"
    }
}
