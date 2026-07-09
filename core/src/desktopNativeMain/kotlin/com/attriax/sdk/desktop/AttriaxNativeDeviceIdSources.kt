package com.attriax.sdk.desktop

import com.attriax.sdk.internal.DeviceIdSources

/**
 * Kotlin/Native desktop [DeviceIdSources], identical to the JVM
 * [com.attriax.sdk.jvm.AttriaxDesktopDeviceIdSources]: a desktop host has neither
 * an Android SSAID nor a Play advertising id, so BOTH native candidates return
 * `null`. This makes [com.attriax.sdk.internal.AttriaxDeviceIdentityResolver] fall
 * through to the persistent generated fallback id (`source = persistent_storage`),
 * which is the correct and stable desktop behavior — the same generated id is
 * reused across restarts via the file store.
 */
class AttriaxNativeDeviceIdSources : DeviceIdSources {

    override fun androidSsaid(): String? = null

    override fun advertisingId(): String? = null
}
