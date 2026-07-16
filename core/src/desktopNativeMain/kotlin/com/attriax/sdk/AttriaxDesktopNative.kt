package com.attriax.sdk

import com.attriax.sdk.desktop.AttriaxKtorHttpClient
import com.attriax.sdk.desktop.AttriaxNativeBrowserOpener
import com.attriax.sdk.desktop.AttriaxNativeConnectivityMonitor
import com.attriax.sdk.desktop.AttriaxNativeDeviceIdSources
import com.attriax.sdk.desktop.AttriaxNativeFileKeyValueStore
import com.attriax.sdk.desktop.AttriaxNativeScheduler
import com.attriax.sdk.desktop.joinPath
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.AttriaxProjectScopedKeyValueStore
import com.attriax.sdk.internal.AttriaxUserAgent
import com.attriax.sdk.internal.withDeviceContext
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Factory for the Attriax SDK on a Kotlin/Native desktop host (Windows-native via
 * mingwX64, Linux-native via linuxX64). The JVM sibling is [AttriaxDesktop] and the
 * Android sibling is [AttriaxSdk]; this mirrors the JVM wiring but with NATIVE
 * adapters (no JVM, no Android `Context`):
 *
 *  - durable POSIX file persistence (survives restarts): two
 *    [AttriaxNativeFileKeyValueStore] files behind one
 *    [AttriaxProjectScopedKeyValueStore] — a machine-shared device-identity file
 *    plus a per-project-token file for all other state (#78),
 *  - the single long-lived [AttriaxKtorHttpClient] transport (Ktor over the
 *    per-target engine — WinHttp on Windows, Curl on Linux) stamped with the
 * mandatory real, isbot-passing User-Agent (`attriax-native-sdk`),
 *  - [AttriaxNativeConnectivityMonitor] (OS connectivity poll —
 *    `InternetGetConnectedState` on Windows, `getifaddrs` on Linux; fires the
 * offline→online restore re-flush),
 *  - the device-identity resolver over [AttriaxNativeDeviceIdSources] (no
 *    SSAID/GAID → the persistent generated fallback id),
 *  - [AttriaxNativeScheduler] for the off-thread session heartbeat / deferred flush, and
 *  - [AttriaxNativeBrowserOpener] (`ShellExecuteW` on Windows, `xdg-open` on Linux)
 * for deep-link browser-fallback opens.
 *
 * Install-referrer, attestation, and lifecycle-binding stay at their engine defaults
 * (Unavailable / Noop): a native desktop host has no Play services and no
 * process-lifecycle owner. Call [Attriax.init] afterwards to bootstrap.
 */
object AttriaxDesktopNative {
    /** SDK release version (shared with the Flutter/Android/JVM reference). */
    const val VERSION: String = AttriaxVersion.PACKAGE_VERSION

    /**
     * Build a runtime for [config], persisting under [dataDir]
     * (default `<home>/.attriax`). Call [Attriax.init] afterwards.
     */
    fun create(
        config: AttriaxConfig,
        dataDir: String = defaultDataDir(),
    ): Attriax {
        val store = createProjectScopedStore(
            dataDir = dataDir,
            projectToken = config.normalizedProjectToken,
            appPackageName = config.appPackageName,
        )

        val snapshot = captureContext(config)
        val userAgent = AttriaxUserAgent.format(
            osVersion = snapshot.osVersion,
            descriptor = snapshot.userAgentDescriptor(),
            client = "attriax-native-sdk",
            osName = osName(),
        )

        val transport = AttriaxKtorHttpClient(
            baseUrl = config.apiBaseUrl,
            userAgent = userAgent,
            requestTimeoutMs = config.requestTimeoutMs,
        )

        // No SSAID/GAID on desktop → resolver falls through to the persistent
        // generated id, stored durably in the file store above.
        val sources = AttriaxNativeDeviceIdSources()
        val resolver = AttriaxDeviceIdentityResolver(sources, config.collectAdvertisingId)
        val deviceIdentityStore = AttriaxDeviceIdentityStore(store, resolver)

        return Attriax(
            config = config,
            store = store,
            transport = transport,
            connectivity = AttriaxNativeConnectivityMonitor(),
            context = snapshot,
            deviceIdentityStore = deviceIdentityStore,
            scheduler = AttriaxNativeScheduler(),
            browserOpener = AttriaxNativeBrowserOpener(),
            // installReferrerProvider / lifecycleBinderFactory are left at their engine
            // defaults (Unavailable / Noop): desktop has no Play services and no
            // ProcessLifecycleOwner. flush/consent executors default from the native
            // background-executor seam (a real single background thread).
        )
    }

    /**
     * The #78 two-file store layout over [dataDir]: the default-named shared file
     * keeps ONLY the machine-wide device identity (#72 — also the legacy pre-split
     * store, so device ids carry forward untouched); a per-project file (name
     * derived from a hash of [projectToken]) holds all other mutable state, so two
     * apps with different project tokens never share consent, queue, or session.
     * Legacy single-file state is imported into the project file once —
     * see [AttriaxProjectScopedKeyValueStore.importLegacySingleFileState].
     */
    internal fun createProjectScopedStore(
        dataDir: String,
        projectToken: String,
        appPackageName: String?,
    ): AttriaxProjectScopedKeyValueStore {
        val sharedIdentityStore = AttriaxNativeFileKeyValueStore(dataDir)
        val projectStore = AttriaxNativeFileKeyValueStore(
            dataDir,
            AttriaxNativeFileKeyValueStore.projectFileName(projectToken),
        )
        val store = AttriaxProjectScopedKeyValueStore(sharedIdentityStore, projectStore)
        store.importLegacySingleFileState(sharedIdentityStore.keys(), appPackageName)
        return store
    }

    /** `<home>/.attriax`, falling back to the working directory when no home is set. */
    @OptIn(ExperimentalForeignApi::class)
    fun defaultDataDir(): String {
        // HOME on Linux/macOS, USERPROFILE on Windows; both read via POSIX getenv.
        val home = getenv("HOME")?.toKString()?.takeIf { it.isNotBlank() }
            ?: getenv("USERPROFILE")?.toKString()?.takeIf { it.isNotBlank() }
            ?: "."
        return joinPath(home, ".attriax")
    }

    private fun captureContext(config: AttriaxConfig): AttriaxContextSnapshot {
        val autoCaptured = AttriaxContextSnapshot(
            packageName = config.appPackageName,
            appVersion = config.appVersion,
            appBuildNumber = config.appBuildNumber,
            // No reliable model/manufacturer on a generic native desktop host; leave
            // both null (omitted from the wire), matching the JVM desktop factory.
            deviceModel = null,
            deviceManufacturer = null,
            // OS version capture on native has no portable, dependency-free API here;
            // report "unknown" (honest fallback, same as the JVM factory when os.version
            // is blank). A per-target uname/RtlGetVersion probe can refine this later.
            osVersion = osVersion(),
            deviceTimezone = envValue("TZ"),
            deviceLocale = envValue("LC_ALL") ?: envValue("LANG"),
            deviceIsPhysical = true,
            platform = platformSlug(),
        )
        // A wrapper-supplied context wins over auto-capture (wrappers know best).
        return autoCaptured.withDeviceContext(config.deviceContext)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun envValue(name: String): String? =
        getenv(name)?.toKString()?.takeIf { it.isNotBlank() }

    /** Best-effort native OS version; "unknown" when no portable source is available. */
    private fun osVersion(): String = "unknown"

    /** The human OS name for the UA: "Windows" / "Linux", else "Native". */
    @OptIn(ExperimentalNativeApi::class)
    private fun osName(): String = when (Platform.osFamily) {
        OsFamily.WINDOWS -> "Windows"
        OsFamily.LINUX -> "Linux"
        OsFamily.MACOSX -> "macOS"
        else -> "Native"
    }

    /**
     * A stable, lowercased platform slug shipped as the app-open `platform`:
     * "windows" / "linux" (/ "macos"), else "native".
     */
    @OptIn(ExperimentalNativeApi::class)
    private fun platformSlug(): String = when (Platform.osFamily) {
        OsFamily.WINDOWS -> "windows"
        OsFamily.LINUX -> "linux"
        OsFamily.MACOSX -> "macos"
        else -> "native"
    }
}
