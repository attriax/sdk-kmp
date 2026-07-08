package com.attriax.sdk

import android.content.Context
import android.os.Build
import com.attriax.sdk.android.AttriaxAndroidDeviceIdSources
import com.attriax.sdk.android.AttriaxConnectivityMonitor
import com.attriax.sdk.android.AttriaxExecutorScheduler
import com.attriax.sdk.android.AttriaxOkHttpClient
import com.attriax.sdk.android.AttriaxProcessLifecycleObserver
import com.attriax.sdk.android.AttriaxSharedPreferencesStore
import com.attriax.sdk.internal.AttriaxLifecycleBinder
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.withDeviceContext
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.AttriaxUserAgent
import java.util.Locale
import java.util.TimeZone

/**
 * Factory for the Attriax Android SDK (Epic 9.2 — standalone Kotlin core).
 *
 * [create] assembles a fully-wired [Attriax] runtime from an Android [Context]:
 * SharedPreferences persistence, the single long-lived OkHttp transport stamped
 * with the mandatory real User-Agent (PARITY §8), connectivity monitoring, and
 * the device-identity resolver.
 */
object AttriaxSdk {
    /** SDK release version (mirrors the Flutter reference; PARITY row I3). */
    const val VERSION: String = AttriaxVersion.PACKAGE_VERSION

    /**
     * Build a runtime for [config]. Call [Attriax.init] afterwards to bootstrap.
     *
     * @param advertisingIdSupplier optional off-main-thread supplier of the Play
     *   Services advertising id; when absent, resolution falls through to the
     *   persistent-storage device id.
     */
    fun create(
        context: Context,
        config: AttriaxConfig,
        advertisingIdSupplier: () -> String? = { null },
    ): Attriax {
        val appContext = context.applicationContext
        val store = AttriaxSharedPreferencesStore(appContext)

        val snapshot = captureContext(appContext, config)
        val userAgent = AttriaxUserAgent.format(
            osVersion = snapshot.osVersion,
            descriptor = snapshot.userAgentDescriptor(),
        )

        val transport = AttriaxOkHttpClient(
            baseUrl = config.apiBaseUrl,
            userAgent = userAgent,
            requestTimeoutMs = config.requestTimeoutMs,
        )

        val sources = AttriaxAndroidDeviceIdSources(
            context = appContext,
            collectAdvertisingId = config.collectAdvertisingId,
            advertisingIdSupplier = advertisingIdSupplier,
        )
        val resolver = AttriaxDeviceIdentityResolver(sources, config.collectAdvertisingId)
        val deviceIdentityStore = AttriaxDeviceIdentityStore(store, resolver)

        return Attriax(
            config = config,
            store = store,
            transport = transport,
            connectivity = AttriaxConnectivityMonitor(appContext),
            context = snapshot,
            deviceIdentityStore = deviceIdentityStore,
            // Session heartbeat timer runs off the main thread; foreground/background
            // detection is bound via ProcessLifecycleOwner (PARITY §3, row S3).
            scheduler = AttriaxExecutorScheduler(),
            // Google Play install-referrer capture (PARITY §3). Always injected; the
            // engine/coordinator gates on config.installReferrerEnabled and degrades
            // silently on non-Play builds where the client is unavailable.
            installReferrerProvider =
                com.attriax.sdk.android.AttriaxPlayInstallReferrerProvider(appContext),
            lifecycleBinderFactory = { lifecycleManager ->
                val observer = AttriaxProcessLifecycleObserver(lifecycleManager)
                object : AttriaxLifecycleBinder {
                    override fun bind() = observer.register()
                    override fun unbind() = observer.unregister()
                }
            },
        )
    }

    private fun captureContext(context: Context, config: AttriaxConfig): AttriaxContextSnapshot {
        val packageName = config.appPackageName ?: context.packageName
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels.takeIf { it > 0 }
        val screenHeight = metrics.heightPixels.takeIf { it > 0 }
        val supportedAbis = Build.SUPPORTED_ABIS?.toList()?.takeIf { it.isNotEmpty() }
        val autoCaptured = AttriaxContextSnapshot(
            packageName = packageName,
            appVersion = config.appVersion,
            appBuildNumber = config.appBuildNumber,
            deviceModel = Build.MODEL,
            deviceManufacturer = Build.MANUFACTURER,
            osVersion = Build.VERSION.RELEASE ?: "unknown",
            deviceTimezone = TimeZone.getDefault().id,
            deviceLocale = Locale.getDefault().toLanguageTag(),
            // Non-sensitive device enrichment auto-captured from Build/Resources
            // (DeviceContextDto parity). advertisingId/androidId are intentionally
            // NOT auto-captured here — device identity flows via the resolver.
            deviceBrand = Build.BRAND?.takeIf { it.isNotBlank() },
            deviceHardware = Build.HARDWARE?.takeIf { it.isNotBlank() },
            deviceName = (Build.DEVICE ?: Build.PRODUCT)?.takeIf { it.isNotBlank() },
            deviceIsPhysical = !isProbablyEmulator(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            screenResolution = if (screenWidth != null && screenHeight != null) {
                "${screenWidth}x$screenHeight"
            } else {
                null
            },
            devicePixelRatio = metrics.density.toDouble().takeIf { it > 0.0 },
            supportedAbis = supportedAbis,
        )
        // A wrapper-supplied context wins over auto-capture (wrappers know best).
        return autoCaptured.withDeviceContext(config.deviceContext)
    }

    /**
     * Cheap emulator heuristic driven by Build fingerprint/product/model markers.
     * Errs toward "physical" for real hardware; only trips on the well-known
     * emulator signatures (generic fingerprints, `sdk_gphone*`/`google_sdk`, etc.).
     */
    private fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val product = Build.PRODUCT ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val brand = Build.BRAND ?: ""
        val device = Build.DEVICE ?: ""
        return fingerprint.startsWith("generic") ||
            fingerprint.startsWith("unknown") ||
            fingerprint.contains("emulator") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            manufacturer.contains("Genymotion") ||
            (brand.startsWith("generic") && device.startsWith("generic")) ||
            product == "google_sdk" ||
            product.contains("sdk_gphone") ||
            product.contains("vbox") ||
            product.contains("emulator") ||
            product.contains("simulator")
    }
}
