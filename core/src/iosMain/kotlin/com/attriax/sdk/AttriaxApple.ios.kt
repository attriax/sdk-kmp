@file:OptIn(ExperimentalForeignApi::class)

package com.attriax.sdk

import com.attriax.sdk.apple.AttriaxIosDeviceIdSources
import com.attriax.sdk.apple.AttriaxIosLifecycleBinder
import com.attriax.sdk.apple.attriaxAttGatedIdfa
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdSource
import com.attriax.sdk.internal.AttriaxLifecycleBinder
import com.attriax.sdk.internal.DeviceIdSources
import com.attriax.sdk.internal.session.AttriaxSessionLifecycleManager
import com.attriax.sdk.internal.withDeviceContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.localTimeZone
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.getenv
import platform.posix.uname
import platform.posix.utsname

internal actual fun appleContextSnapshot(config: AttriaxConfig): AttriaxContextSnapshot {
    val device = UIDevice.currentDevice
    val bundle = NSBundle.mainBundle
    val simulator = getenv("SIMULATOR_UDID") != null

    val autoCaptured = AttriaxContextSnapshot(
        packageName = config.appPackageName ?: bundle.bundleIdentifier,
        appVersion = config.appVersion
            ?: (bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String),
        appBuildNumber = config.appBuildNumber
            ?: (bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String),
        // Hardware identifier (e.g. "iPhone15,2"); UIDevice.model is only "iPhone".
        deviceModel = hardwareMachine() ?: device.model,
        deviceManufacturer = "Apple",
        osVersion = device.systemVersion,
        deviceTimezone = NSTimeZone.localTimeZone.name,
        deviceLocale = NSLocale.currentLocale.localeIdentifier,
        deviceIsPhysical = !simulator,
        platform = "ios",
    )
    // A wrapper-supplied context wins over auto-capture (the wrapper knows best).
    return autoCaptured.withDeviceContext(config.deviceContext)
}

internal actual fun appleDeviceIdSources(
    collectAdvertisingId: Boolean,
    attStatus: () -> AttriaxAttStatus,
): DeviceIdSources = AttriaxIosDeviceIdSources(
    collectAdvertisingId = collectAdvertisingId,
    advertisingIdSupplier = { attriaxAttGatedIdfa(attStatus() == AttriaxAttStatus.AUTHORIZED) },
)

internal actual fun appleAdvertisingIdSource(): String = AttriaxDeviceIdSource.IOS_IDFA

internal actual fun appleLifecycleBinder(
    manager: AttriaxSessionLifecycleManager,
): AttriaxLifecycleBinder = AttriaxIosLifecycleBinder(manager)

internal actual fun appleBrowserOpener(): AttriaxBrowserOpener = AttriaxBrowserOpener { urlString ->
    val url = NSURL.URLWithString(urlString) ?: return@AttriaxBrowserOpener false
    // UIApplication must be touched on the main thread; dispatch and report dispatched.
    dispatch_async(dispatch_get_main_queue()) {
        UIApplication.sharedApplication.openURL(
            url = url,
            options = emptyMap<Any?, Any?>(),
            completionHandler = null,
        )
    }
    true
}

/** The hardware machine identifier (e.g. "iPhone15,2") via `uname().machine`. */
private fun hardwareMachine(): String? = memScoped {
    val info = alloc<utsname>()
    if (uname(info.ptr) != 0) return@memScoped null
    info.machine.toKString().takeIf { it.isNotBlank() }
}
