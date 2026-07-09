package com.attriax.sdk.apple

import com.attriax.sdk.internal.DeviceIdSources

/**
 * macOS [DeviceIdSources]. macOS has no `identifierForVendor` and no IDFA, so both
 * native candidates return null and device-identity resolution falls through to the
 * persistent generated fallback id (`source = persistent_storage`) — the correct,
 * stable macOS behavior, matching the desktop `AttriaxNativeDeviceIdSources`. (A
 * hardware `IOPlatformUUID`-based id could refine this later.)
 */
class AttriaxMacosDeviceIdSources : DeviceIdSources {
    override fun androidSsaid(): String? = null
    override fun advertisingId(): String? = null
    // iosIdfv() defaults to null (no IDFV on macOS).
}
