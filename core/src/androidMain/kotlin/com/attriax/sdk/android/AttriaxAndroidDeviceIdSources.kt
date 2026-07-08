package com.attriax.sdk.android

import android.content.Context
import android.provider.Settings
import com.attriax.sdk.internal.DeviceIdSources

/**
 * [DeviceIdSources] backed by the Android platform (PARITY §2, row D4).
 *
 *  - `androidSsaid()` reads Settings.Secure ANDROID_ID (SSAID).
 *  - `advertisingId()` returns null unless [collectAdvertisingId] is true AND a
 *    Play Services advertising id has been supplied via [advertisingIdSupplier].
 *
 * The advertising id is intentionally injected rather than resolved inline: the
 * Play Services `AdvertisingIdClient` call must run off the main thread and is an
 * optional dependency, so the host wires it in when available. When absent,
 * resolution falls through to the persistent-storage id.
 */
class AttriaxAndroidDeviceIdSources(
    context: Context,
    private val collectAdvertisingId: Boolean,
    private val advertisingIdSupplier: () -> String? = { null },
) : DeviceIdSources {

    private val appContext = context.applicationContext

    override fun androidSsaid(): String? = try {
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (e: Exception) {
        null
    }

    override fun advertisingId(): String? {
        if (!collectAdvertisingId) return null
        return try {
            advertisingIdSupplier()
        } catch (e: Exception) {
            null
        }
    }
}
