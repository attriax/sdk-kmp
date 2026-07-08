package com.attriax.sdk.jvm

import com.attriax.sdk.internal.DeviceIdSources

/**
 * Desktop [DeviceIdSources]. A JVM desktop host has neither an Android SSAID
 * (`Settings.Secure.ANDROID_ID`) nor a Play advertising id (GAID), so BOTH native
 * candidates return `null`. This makes [com.attriax.sdk.internal.AttriaxDeviceIdentityResolver]
 * fall through to the persistent generated fallback id
 * (`source = persistent_storage`), which is the correct and stable desktop
 * behavior — the same generated id is reused across restarts via the file store.
 */
class AttriaxDesktopDeviceIdSources : DeviceIdSources {

    override fun androidSsaid(): String? = null

    override fun advertisingId(): String? = null
}
