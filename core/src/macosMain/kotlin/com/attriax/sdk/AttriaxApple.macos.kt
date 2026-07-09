@file:OptIn(ExperimentalForeignApi::class)

package com.attriax.sdk

import com.attriax.sdk.apple.AttriaxMacosDeviceIdSources
import com.attriax.sdk.apple.AttriaxMacosLifecycleBinder
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
import kotlinx.cinterop.useContents
import platform.AppKit.NSWorkspace
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.localTimeZone
import platform.posix.uname
import platform.posix.utsname

internal actual fun appleContextSnapshot(config: AttriaxConfig): AttriaxContextSnapshot {
    val bundle = NSBundle.mainBundle

    val autoCaptured = AttriaxContextSnapshot(
        packageName = config.appPackageName ?: bundle.bundleIdentifier,
        appVersion = config.appVersion
            ?: (bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String),
        appBuildNumber = config.appBuildNumber
            ?: (bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String),
        // Mac CPU arch, e.g. "arm64" / "x86_64" (the fine-grained model id needs
        // sysctlbyname, which K/N's posix does not expose on Apple targets).
        deviceModel = hardwareModel() ?: "Mac",
        deviceManufacturer = "Apple",
        osVersion = macOsVersion(),
        deviceTimezone = NSTimeZone.localTimeZone.name,
        deviceLocale = NSLocale.currentLocale.localeIdentifier,
        deviceIsPhysical = true,
        platform = "macos",
    )
    return autoCaptured.withDeviceContext(config.deviceContext)
}

internal actual fun appleDeviceIdSources(
    collectAdvertisingId: Boolean,
    attStatus: () -> AttriaxAttStatus,
): DeviceIdSources = AttriaxMacosDeviceIdSources()

// macOS has no advertising id, so the resolver never reaches the advertising branch;
// return the default slug for symmetry (unreachable on macOS).
internal actual fun appleAdvertisingIdSource(): String = AttriaxDeviceIdSource.ANDROID_GAID

internal actual fun appleLifecycleBinder(
    manager: AttriaxSessionLifecycleManager,
): AttriaxLifecycleBinder = AttriaxMacosLifecycleBinder(manager)

internal actual fun appleBrowserOpener(): AttriaxBrowserOpener = AttriaxBrowserOpener { urlString ->
    val url = NSURL.URLWithString(urlString) ?: return@AttriaxBrowserOpener false
    NSWorkspace.sharedWorkspace.openURL(url)
}

private fun macOsVersion(): String =
    NSProcessInfo.processInfo.operatingSystemVersion.useContents {
        "$majorVersion.$minorVersion.$patchVersion"
    }

/** The Mac CPU arch (e.g. "arm64"/"x86_64") via `uname().machine`. */
private fun hardwareModel(): String? = memScoped {
    val info = alloc<utsname>()
    if (uname(info.ptr) != 0) return@memScoped null
    info.machine.toKString().takeIf { it.isNotBlank() }
}
