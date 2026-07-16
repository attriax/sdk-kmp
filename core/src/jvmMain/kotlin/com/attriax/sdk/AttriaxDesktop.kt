package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.AttriaxProjectScopedKeyValueStore
import com.attriax.sdk.internal.AttriaxUserAgent
import com.attriax.sdk.internal.withDeviceContext
import com.attriax.sdk.jvm.AttriaxDesktopConnectivityMonitor
import com.attriax.sdk.jvm.AttriaxDesktopDeviceIdSources
import com.attriax.sdk.jvm.AttriaxFileKeyValueStore
import com.attriax.sdk.jvm.AttriaxJvmBrowserOpener
import com.attriax.sdk.jvm.AttriaxJvmHttpClient
import com.attriax.sdk.jvm.AttriaxJvmScheduler
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Factory for the Attriax SDK on a JVM desktop host (Windows / macOS / Linux via a
 * JVM). The Android sibling is [AttriaxSdk]; this mirrors its wiring but WITHOUT an
 * Android `Context`:
 *
 *  - durable file persistence (survives restarts): two [AttriaxFileKeyValueStore]
 *    files behind one [AttriaxProjectScopedKeyValueStore] — a machine-shared
 *    device-identity file plus a per-project-token file for all other state (#78),
 *  - the single long-lived [AttriaxJvmHttpClient] transport (pure JDK
 * `HttpURLConnection`) stamped with the mandatory real User-Agent,
 *  - [AttriaxDesktopConnectivityMonitor] (NetworkInterface poll; fires the
 * offline→online restore re-flush),
 *  - the device-identity resolver over [AttriaxDesktopDeviceIdSources] (no
 *    SSAID/GAID → the persistent generated fallback id),
 *  - [AttriaxJvmScheduler] for the off-thread session heartbeat / deferred flush, and
 *  - [AttriaxJvmBrowserOpener] (`java.awt.Desktop.browse`) for deep-link
 * browser-fallback opens.
 *
 * Install-referrer, attestation, and lifecycle-binding stay at their engine defaults
 * (Unavailable / Noop): a JVM desktop has no Play services and no process-lifecycle
 * owner. Call [Attriax.init] afterwards to bootstrap.
 */
object AttriaxDesktop {
    /** SDK release version (shared with the Flutter/Android reference). */
    const val VERSION: String = AttriaxVersion.PACKAGE_VERSION

    /**
     * Build a runtime for [config], persisting under [dataDir]
     * (default `~/.attriax`). Call [Attriax.init] afterwards.
     */
    fun create(
        config: AttriaxConfig,
        dataDir: File = defaultDataDir(),
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
            client = "attriax-jvm-sdk",
            osName = osName(),
        )

        val transport = AttriaxJvmHttpClient(
            baseUrl = config.apiBaseUrl,
            userAgent = userAgent,
            requestTimeoutMs = config.requestTimeoutMs,
        )

        // No SSAID/GAID on desktop → resolver falls through to the persistent
        // generated id, stored durably in the file store above.
        val sources = AttriaxDesktopDeviceIdSources()
        val resolver = AttriaxDeviceIdentityResolver(sources, config.collectAdvertisingId)
        val deviceIdentityStore = AttriaxDeviceIdentityStore(store, resolver)

        return Attriax(
            config = config,
            store = store,
            transport = transport,
            connectivity = AttriaxDesktopConnectivityMonitor(),
            context = snapshot,
            deviceIdentityStore = deviceIdentityStore,
            scheduler = AttriaxJvmScheduler(),
            browserOpener = AttriaxJvmBrowserOpener(),
            // installReferrerProvider / lifecycleBinderFactory are left at their engine
            // defaults (Unavailable / Noop): desktop has no Play services and no
            // ProcessLifecycleOwner. flush/consent executors default from the jvm
            // background-executor seam.
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
        dataDir: File,
        projectToken: String,
        appPackageName: String?,
    ): AttriaxProjectScopedKeyValueStore {
        val sharedIdentityStore = AttriaxFileKeyValueStore(dataDir)
        val projectStore = AttriaxFileKeyValueStore(
            dataDir,
            AttriaxFileKeyValueStore.projectFileName(projectToken),
        )
        val store = AttriaxProjectScopedKeyValueStore(sharedIdentityStore, projectStore)
        store.importLegacySingleFileState(sharedIdentityStore.keys(), appPackageName)
        return store
    }

    /** `~/.attriax`, falling back to the working directory when no user home is set. */
    fun defaultDataDir(): File {
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() } ?: "."
        return File(home, ".attriax")
    }

    private fun captureContext(config: AttriaxConfig): AttriaxContextSnapshot {
        val autoCaptured = AttriaxContextSnapshot(
            packageName = config.appPackageName,
            appVersion = config.appVersion,
            appBuildNumber = config.appBuildNumber,
            // No reliable model/manufacturer on a generic JVM; report the JVM vendor as
            // manufacturer and leave model null (omitted from the wire).
            deviceModel = null,
            deviceManufacturer = System.getProperty("java.vendor")?.takeIf { it.isNotBlank() },
            osVersion = System.getProperty("os.version")?.takeIf { it.isNotBlank() } ?: "unknown",
            deviceTimezone = TimeZone.getDefault().id,
            deviceLocale = Locale.getDefault().toLanguageTag(),
            deviceIsPhysical = true,
            platform = platformSlug(),
        )
        // A wrapper-supplied context wins over auto-capture (wrappers know best).
        return autoCaptured.withDeviceContext(config.deviceContext)
    }

    /** The raw JVM `os.name` (e.g. "Windows 11", "Mac OS X", "Linux") for the UA. */
    private fun osName(): String =
        System.getProperty("os.name")?.takeIf { it.isNotBlank() } ?: "JVM"

    /**
     * A stable, lowercased platform slug derived from `os.name`:
     * "windows" / "macos" / "linux", else "jvm". Shipped as the app-open `platform`.
     */
    private fun platformSlug(): String {
        val os = (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT)
        return when {
            os.contains("win") -> "windows"
            os.contains("mac") || os.contains("darwin") || os.contains("os x") -> "macos"
            os.contains("nux") || os.contains("nix") || os.contains("aix") -> "linux"
            else -> "jvm"
        }
    }
}
