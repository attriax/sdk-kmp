package com.attriax.sdk.apple

import com.attriax.sdk.internal.DeviceIdSources
import platform.UIKit.UIDevice

/**
 * [DeviceIdSources] backed by the iOS platform (PARITY §2, row D4) — the port of the
 * standalone iOS SDK's `AttriaxIOSDeviceIdSources`.
 *
 *  - [iosIdfv] reads `UIDevice.identifierForVendor` (IDFV) → resolver source
 *    `ios_idfv`.
 *  - [advertisingId] returns the IDFA ONLY when [collectAdvertisingId] is true AND
 *    the injected [advertisingIdSupplier] yields an ATT-authorized, non-zero IDFA
 *    (the ATT-gated supplier is wired by the factory in the framework-seam phase).
 *
 * The IDFA is INJECTED rather than resolved inline so this file references no
 * AdSupport / AppTrackingTransparency symbol: reading `ASIdentifierManager` is only
 * meaningful once ATT is `.authorized`, which is the framework-seam concern. Until a
 * supplier is wired, resolution falls through IDFV → persistent storage.
 */
class AttriaxIosDeviceIdSources(
    private val collectAdvertisingId: Boolean,
    private val advertisingIdSupplier: () -> String? = { null },
) : DeviceIdSources {

    override fun androidSsaid(): String? = null

    override fun iosIdfv(): String? =
        UIDevice.currentDevice.identifierForVendor?.UUIDString

    override fun advertisingId(): String? =
        if (collectAdvertisingId) advertisingIdSupplier() else null
}
