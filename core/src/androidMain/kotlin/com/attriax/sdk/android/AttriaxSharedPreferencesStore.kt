package com.attriax.sdk.android

import android.content.Context
import android.content.SharedPreferences
import com.attriax.sdk.internal.KeyValueStore

/**
 * [KeyValueStore] backed by a private [SharedPreferences] file. This is the only
 * production key/value persistence; the pure engine and its JVM tests depend on
 * the [KeyValueStore] interface, never on this class.
 */
class AttriaxSharedPreferencesStore(context: Context) : KeyValueStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, value).apply()
        }
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val PREFS_NAME = "com.attriax.sdk.prefs"
    }
}
