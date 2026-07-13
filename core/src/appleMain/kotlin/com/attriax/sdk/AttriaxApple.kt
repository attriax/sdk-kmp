package com.attriax.sdk

import com.attriax.sdk.apple.AttriaxAppleConnectivityMonitor
import com.attriax.sdk.apple.AttriaxAppleScheduler
import com.attriax.sdk.apple.AttriaxAppleUrlSessionHttpClient
import com.attriax.sdk.apple.AttriaxAppleUserAgent
import com.attriax.sdk.apple.AttriaxAppleUserDefaultsStore
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.AttriaxLifecycleBinder
import com.attriax.sdk.internal.DeviceIdSources
import com.attriax.sdk.internal.attriaxAttStatus
import com.attriax.sdk.internal.session.AttriaxSessionLifecycleManager

/**
 * Factory for the Attriax SDK on Apple platforms (iOS device + simulator, macOS) —
 * the sibling of [AttriaxSdk] (Android), [AttriaxDesktop] (JVM), and
 * [AttriaxDesktopNative] (Windows/Linux native). Wires the Apple adapters +
 * framework seams into the shared [Attriax] engine:
 *
 *  - [AttriaxAppleUserDefaultsStore] durable suite-scoped NSUserDefaults persistence,
 *  - the single long-lived [AttriaxAppleUrlSessionHttpClient] (NSURLSession) stamped
 * with the mandatory REAL WKWebView Safari User-Agent (see
 *    [AttriaxAppleUserAgent] — wrapper-supplied first, else a live probe / Safari
 *    fallback, never a synthetic slug),
 *  - [AttriaxAppleConnectivityMonitor] (NWPathMonitor) for the offline→online
 *    re-flush,
 *  - the device-identity resolver over the platform [DeviceIdSources]
 *    (`identifierForVendor` + the ATT-gated IDFA on iOS; persistent fallback on
 *    macOS),
 *  - [AttriaxAppleScheduler] for the off-thread session heartbeat / deferred flush,
 *  - the platform lifecycle binder (UIApplication on iOS / NSApplication on macOS)
 *    and browser opener.
 *
 * The ATT / SKAN / ASA platform seams are the real Apple actuals (App Tracking
 * Transparency / SKAdNetwork / AdServices). App Attest is opt-in via
 * [AttriaxConfig.attestationProvider] (supply
 * [com.attriax.sdk.apple.AttriaxAppAttestProvider]). Call [Attriax.init] afterwards
 * to bootstrap.
 */
object AttriaxApple {
    /** SDK release version (shared with the Flutter/Android/JVM/native reference). */
    const val VERSION: String = AttriaxVersion.PACKAGE_VERSION

    /**
     * Build a runtime for [config]. [userAgent] is the wrapper-supplied real WKWebView
     * Safari UA (the Flutter/Unity iOS shim captures it); when null the Apple layer
     * resolves the UA itself (live WKWebView probe off the main thread, else a
     * Safari-shaped fallback).
     *
     * [advertisingIdSupplier] lets a host provide the advertising id (IDFA) itself —
     * e.g. a wrapper that already read `ASIdentifierManager` under its own ATT flow.
     * When non-null AND [AttriaxConfig.collectAdvertisingId] is true, its value is
     * used AHEAD of the internal ATT-gated IDFA resolution (which is consulted only
     * when the supplier yields null/blank). When null, resolution falls back entirely
     * to the internal ATT-gated seam. Call [Attriax.init] afterwards.
     */
    fun create(
        config: AttriaxConfig,
        userAgent: String? = null,
        advertisingIdSupplier: (() -> String?)? = null,
    ): Attriax {
        val store = AttriaxAppleUserDefaultsStore()

        val snapshot = appleContextSnapshot(config)
        val resolvedUserAgent = AttriaxAppleUserAgent.resolve(userAgent, snapshot.osVersion)

        val transport = AttriaxAppleUrlSessionHttpClient(
            baseUrl = config.apiBaseUrl,
            userAgent = resolvedUserAgent,
            requestTimeoutMs = config.requestTimeoutMs,
        )

        // IDFA is gated on ATT authorization: prefer a wrapper-supplied ATT status,
        // else read the platform ATT seam (never prompts). A caller-provided
        // advertisingIdSupplier (when present) overrides that internal resolution.
        val attStatusProvider: () -> AttriaxAttStatus = { config.attStatus ?: attriaxAttStatus() }
        val platformSources = appleDeviceIdSources(config.collectAdvertisingId, attStatusProvider)
        val sources = if (advertisingIdSupplier != null) {
            AttriaxSuppliedAdvertisingIdSources(platformSources, advertisingIdSupplier)
        } else {
            platformSources
        }
        val resolver = AttriaxDeviceIdentityResolver(
            sources = sources,
            collectAdvertisingId = config.collectAdvertisingId,
            advertisingIdSource = appleAdvertisingIdSource(),
        )
        val deviceIdentityStore = AttriaxDeviceIdentityStore(store, resolver)

        return Attriax(
            config = config,
            store = store,
            transport = transport,
            connectivity = AttriaxAppleConnectivityMonitor(),
            context = snapshot,
            deviceIdentityStore = deviceIdentityStore,
            scheduler = AttriaxAppleScheduler(),
            lifecycleBinderFactory = { manager -> appleLifecycleBinder(manager) },
            browserOpener = appleBrowserOpener(),
            // flush/consent executors + ATT/SKAN/ASA seams default from the platform
            // expect funs (the real Apple actuals). install-referrer stays Unavailable
            // (Apple has no Play install referrer).
        )
    }
}

/**
 * Auto-capture the device context on the current Apple platform (device model, OS
 * version, timezone, locale, bundle id/version, physical/simulator, platform slug),
 * then merge the wrapper-supplied [AttriaxConfig.deviceContext] over it (the wrapper
 * wins). iOS reads UIKit/NSBundle; macOS reads AppKit/ProcessInfo.
 */
internal expect fun appleContextSnapshot(config: AttriaxConfig): AttriaxContextSnapshot

/** The platform [DeviceIdSources] (iOS: IDFV + ATT-gated IDFA; macOS: persistent). */
internal expect fun appleDeviceIdSources(
    collectAdvertisingId: Boolean,
    attStatus: () -> AttriaxAttStatus,
): DeviceIdSources

/** The advertising-id source slug for the resolver (`ios_idfa` on iOS). */
internal expect fun appleAdvertisingIdSource(): String

/** The platform lifecycle binder (UIApplication on iOS / NSApplication on macOS). */
internal expect fun appleLifecycleBinder(
    manager: AttriaxSessionLifecycleManager,
): AttriaxLifecycleBinder

/** The platform browser opener for deep-link browser-fallback URLs. */
internal expect fun appleBrowserOpener(): AttriaxBrowserOpener

/**
 * [DeviceIdSources] decorator that honors a caller-supplied advertising-id
 * [supplier] AHEAD of the wrapped platform source. The supplier value wins when it
 * yields a non-blank id; otherwise resolution falls through to [delegate]'s internal
 * (ATT-gated) advertising id. The primary native ids ([iosIdfv]/[androidSsaid]) are
 * passed straight through — the supplier only participates in the advertising-id
 * step, and only when [AttriaxConfig.collectAdvertisingId] is true (the resolver
 * skips the advertising candidate entirely when collection is off).
 */
private class AttriaxSuppliedAdvertisingIdSources(
    private val delegate: DeviceIdSources,
    private val supplier: () -> String?,
) : DeviceIdSources {
    override fun androidSsaid(): String? = delegate.androidSsaid()
    override fun iosIdfv(): String? = delegate.iosIdfv()
    override fun advertisingId(): String? =
        supplier()?.takeIf { it.isNotBlank() } ?: delegate.advertisingId()
}
