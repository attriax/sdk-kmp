package com.attriax.sdk.apple

import com.attriax.sdk.internal.KeyValueStore
import platform.Foundation.NSUserDefaults

/**
 * [KeyValueStore] backed by a suite-scoped `NSUserDefaults` — the Apple sibling of
 * the Android private `SharedPreferences` store and the JVM/native file stores.
 * All device-id / queue / session / first-launch state persists here and survives
 * process restarts; the pure engine + tests depend only on [KeyValueStore].
 *
 * The suite name namespaces the SDK's keys away from the host app's
 * `standardUserDefaults`, matching both the Android dedicated-prefs file and the
 * standalone iOS SDK's `AttriaxUserDefaultsStore`.
 */
class AttriaxAppleUserDefaultsStore(
    suiteName: String = SUITE_NAME,
) : KeyValueStore {

    // A suite-scoped NSUserDefaults, falling back to standard if the suite cannot be
    // created (very unusual) so persistence still works rather than silently dropping
    // state — parity with the iOS reference.
    private val defaults: NSUserDefaults =
        NSUserDefaults(suiteName = suiteName) ?: NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String?) {
        if (value == null) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(value, forKey = key)
        }
    }

    override fun remove(key: String) = defaults.removeObjectForKey(key)

    companion object {
        const val SUITE_NAME: String = "com.attriax.sdk.prefs"
    }
}
