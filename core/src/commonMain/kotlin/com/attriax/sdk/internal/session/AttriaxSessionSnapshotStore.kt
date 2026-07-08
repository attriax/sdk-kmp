package com.attriax.sdk.internal.session

import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.json.Json

/**
 * Persists the current [AttriaxSessionSnapshot] to a [KeyValueStore] as a small
 * JSON object (PARITY §3, row S5 — "snapshot persisted + revalidated each
 * launch"). Mirrors Flutter `AttriaxSessionStore.readSessionSnapshot` /
 * `setSessionSnapshot`, adapted to the SDK's dependency-free [Json] codec.
 *
 * A corrupt payload is treated as "no session" (returns null) rather than
 * crashing — a bad snapshot must never break init; the launch simply starts a
 * fresh session, exactly as if none had been persisted.
 */
class AttriaxSessionSnapshotStore(private val store: KeyValueStore) {

    /** The persisted snapshot, or null when absent/corrupt. */
    fun read(): AttriaxSessionSnapshot? {
        val raw = store.getString(KEY_SESSION) ?: return null
        return try {
            decode(Json.decodeObject(raw))
        } catch (e: Exception) {
            // Corrupt snapshot → drop it and behave as if no session were stored.
            store.remove(KEY_SESSION)
            null
        }
    }

    /** Persist [snapshot], or clear the stored snapshot when null. */
    fun write(snapshot: AttriaxSessionSnapshot?) {
        if (snapshot == null) {
            store.remove(KEY_SESSION)
            return
        }
        store.putString(KEY_SESSION, Json.encode(encode(snapshot)))
    }

    fun clear() = store.remove(KEY_SESSION)

    companion object {
        const val KEY_SESSION = "attriax.session_snapshot"

        /** Pure snapshot → JSON-map encoding (exposed for tests). */
        fun encode(snapshot: AttriaxSessionSnapshot): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            map["sessionId"] = snapshot.sessionId
            map["startedAtMs"] = snapshot.startedAtMs
            map["lastActivityAtMs"] = snapshot.lastActivityAtMs
            map["heartbeatIntervalMs"] = snapshot.heartbeatIntervalMs
            snapshot.deviceId?.let { map["deviceId"] = it }
            map["platform"] = snapshot.platform
            snapshot.appPackageName?.let { map["appPackageName"] = it }
            snapshot.appVersion?.let { map["appVersion"] = it }
            snapshot.appBuildNumber?.let { map["appBuildNumber"] = it }
            snapshot.locale?.let { map["locale"] = it }
            map["isFirstLaunch"] = snapshot.isFirstLaunch
            snapshot.sdkPackageVersion?.let { map["sdkPackageVersion"] = it }
            return map
        }

        /** Pure JSON-map → snapshot decoding (exposed for tests). Throws on a bad shape. */
        fun decode(map: Map<String, Any?>): AttriaxSessionSnapshot = AttriaxSessionSnapshot(
            sessionId = map["sessionId"] as String,
            startedAtMs = asLong(map["startedAtMs"]),
            lastActivityAtMs = asLong(map["lastActivityAtMs"]),
            heartbeatIntervalMs = asLong(map["heartbeatIntervalMs"]),
            deviceId = map["deviceId"] as? String,
            platform = map["platform"] as String,
            appPackageName = map["appPackageName"] as? String,
            appVersion = map["appVersion"] as? String,
            appBuildNumber = map["appBuildNumber"] as? String,
            locale = map["locale"] as? String,
            isFirstLaunch = (map["isFirstLaunch"] as? Boolean) ?: false,
            sdkPackageVersion = map["sdkPackageVersion"] as? String,
        )

        private fun asLong(value: Any?): Long = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Number -> value.toLong()
            else -> throw IllegalArgumentException("expected numeric, got $value")
        }
    }
}
