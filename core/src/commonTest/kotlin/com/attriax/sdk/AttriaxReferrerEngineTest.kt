package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.ConnectivityMonitor
import com.attriax.sdk.internal.DeviceIdSources
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine-level referrer query API (PARITY — referrer query API). Exercises each
 * public `attriax.referrer` getter against its real backing source: the persisted
 * raw Play referrer, the attribution records the app-open RESPONSE returns and the
 * engine persists, and the session / latest deep-link referrers captured from the
 * deep-link observer stream. A synchronous flush executor drives the app-open +
 * resolve chain inline, so no wall-clock polling is needed.
 */
class AttriaxReferrerEngineTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    private class NoopConnectivity : ConnectivityMonitor {
        override fun isConnected(): Boolean = false
        override fun register(listener: ConnectivityMonitor.Listener) {}
        override fun unregister(listener: ConnectivityMonitor.Listener) {}
    }

    /** Transport returning caller-supplied open + resolve response bodies (unwrapped). */
    private class ConfigurableTransport(
        private val openBody: String,
        private val resolveBody: String = MATCHED_RESOLVE,
    ) : HttpClient {
        override fun post(path: String, body: String): HttpResponse = when (path) {
            "/api/sdk/v1/open" -> HttpResponse(200, openBody)
            "/api/sdk/v1/deep-links/resolve" -> HttpResponse(200, resolveBody)
            else -> HttpResponse(200, "{}")
        }
    }

    private class FixedSources(private val ssaid: String?) : DeviceIdSources {
        override fun androidSsaid(): String? = ssaid
        override fun advertisingId(): String? = null
    }

    private val context = AttriaxContextSnapshot(
        packageName = "com.x",
        appVersion = "1.0.0",
        appBuildNumber = "1",
        deviceModel = "Pixel",
        deviceManufacturer = "Google",
        osVersion = "14",
        deviceTimezone = "UTC",
        deviceLocale = "en-US",
    )

    private fun engine(store: MapStore, transport: HttpClient, firstLaunch: Boolean): Attriax {
        if (!firstLaunch) store.putString("attriax.first_launch_completed", "false")
        val resolver = AttriaxDeviceIdentityResolver(FixedSources("SSAID-123"), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = AttriaxConfig(projectToken = "tok"),
            store = store,
            transport = transport,
            connectivity = NoopConnectivity(),
            context = context,
            deviceIdentityStore = identityStore,
            clock = AttriaxClock { 1_000L },
            flushExecutor = AttriaxTestBackgroundExecutor(),
            consentExecutor = AttriaxTestBackgroundExecutor(),
        ).also { it.init() }
    }

    // -------- raw install referrer --------

    @Test
    fun rawInstallReferrerReadsPersistedKeyTrimmed() {
        val store = MapStore()
        store.putString("attriax.install_referrer", "  utm_source=google&utm_medium=cpc  ")
        val sdk = engine(store, ConfigurableTransport(OPEN_NO_REFERRER), firstLaunch = false)

        assertEquals("utm_source=google&utm_medium=cpc", sdk.referrer.getRawInstallReferrer())
    }

    @Test
    fun rawInstallReferrerNullWhenAbsent() {
        val sdk = engine(MapStore(), ConfigurableTransport(OPEN_NO_REFERRER), firstLaunch = false)
        assertNull(sdk.referrer.getRawInstallReferrer())
    }

    // -------- original vs reinstall install referrer --------

    @Test
    fun originalAndReinstallReferrerFromAppOpenResponse() {
        val sdk = engine(MapStore(), ConfigurableTransport(OPEN_WITH_BOTH_REFERRERS), firstLaunch = true)

        val original = sdk.referrer.getOriginalInstallReferrer()
        assertTrue(original != null)
        assertEquals("google", original.source)
        assertEquals("cpc", original.medium)
        assertEquals("spring_sale", original.campaign)
        assertEquals(AttributionType.REFERRER, original.attributionType)
        assertEquals("utm_source=google", original.rawPlatformInstallReferrer)
        assertEquals(1234L, original.installBeginTimestampSeconds)
        assertEquals(false, original.googlePlayInstantParam)
        assertEquals(0.92, original.precision)

        val reinstall = sdk.referrer.getReinstallReferrer()
        assertTrue(reinstall != null)
        assertEquals("facebook", reinstall.source)
        assertEquals(AttributionType.FINGERPRINT, reinstall.attributionType)
    }

    @Test
    fun reinstallReferrerNullWhenOnlyOriginalPresent() {
        val sdk = engine(MapStore(), ConfigurableTransport(OPEN_WITH_ORIGINAL_ONLY), firstLaunch = true)

        assertTrue(sdk.referrer.getOriginalInstallReferrer() != null)
        assertNull(sdk.referrer.getReinstallReferrer())
    }

    @Test
    fun installReferrerDetailsNullWhenNoneCaptured() {
        val sdk = engine(MapStore(), ConfigurableTransport(OPEN_NO_REFERRER), firstLaunch = true)

        assertNull(sdk.referrer.getOriginalInstallReferrer())
        assertNull(sdk.referrer.getReinstallReferrer())
    }

    @Test
    fun originalReferrerDefaultsAttributionTypeAndPrecision() {
        val sdk = engine(MapStore(), ConfigurableTransport(OPEN_WITH_MINIMAL_REFERRER), firstLaunch = true)

        val original = sdk.referrer.getOriginalInstallReferrer()
        assertTrue(original != null)
        assertEquals(AttributionType.ORGANIC, original.attributionType)
        assertEquals(0.0, original.precision)
        assertNull(original.source)
    }

    // -------- latest deep-link referrer --------

    @Test
    fun latestDeepLinkReferrerAfterForegroundResolve() {
        val sdk = engine(MapStore(), ConfigurableTransport(OPEN_NO_REFERRER), firstLaunch = false)
        assertNull(sdk.referrer.getLatestDeepLinkReferrer())

        sdk.deepLinks.handleUri("https://sub.attriax.com/promo/summer", isInitialLink = false)

        val latest = sdk.referrer.getLatestDeepLinkReferrer()
        assertTrue(latest != null)
        assertEquals("https://sub.attriax.com/promo/summer", latest.uri.toString())
        assertEquals(AttriaxDeepLinkTrigger.FOREGROUND, latest.trigger)
        assertTrue(latest.found)
    }

    // -------- session referrer --------

    @Test
    fun sessionReferrerFromDeferredAppOpen() {
        val sdk = engine(MapStore(), ConfigurableTransport(OPEN_WITH_DEFERRED_LINK), firstLaunch = true)

        val session = sdk.referrer.getSessionReferrer()
        assertTrue(session != null)
        assertEquals("https://sub.attriax.com/deferred/promo", session.uri.toString())
        assertEquals(AttriaxDeepLinkTrigger.DEFERRED, session.trigger)
    }

    @Test
    fun sessionReferrerFromColdStartLink() {
        val sdk = engine(MapStore(), ConfigurableTransport(OPEN_NO_REFERRER), firstLaunch = false)

        sdk.deepLinks.handleUri("https://sub.attriax.com/promo/summer", isInitialLink = true)

        val session = sdk.referrer.getSessionReferrer()
        assertTrue(session != null)
        assertEquals(AttriaxDeepLinkTrigger.COLD_START, session.trigger)
        assertEquals("https://sub.attriax.com/promo/summer", session.uri.toString())
    }

    @Test
    fun sessionReferrerNullWhenNoSessionOpeningLink() {
        val sdk = engine(MapStore(), ConfigurableTransport(OPEN_NO_REFERRER), firstLaunch = false)
        // A foreground link is NOT session-opening, so it must not become the session referrer.
        sdk.deepLinks.handleUri("https://sub.attriax.com/promo/summer", isInitialLink = false)
        // Settle the initial-link probe with no launch link so getSessionReferrer does not block.
        sdk.deepLinks.handleUri("", isInitialLink = true)

        assertNull(sdk.referrer.getSessionReferrer())
    }

    private companion object {
        const val MATCHED_RESOLVE =
            """{"requestVersion":"v1","matched":true,"status":"matched","isFirstLaunch":false,""" +
                """"deepLink":{"path":"promo/summer","uri":"https://sub.attriax.com/promo/summer"}}"""

        const val OPEN_NO_REFERRER =
            """{"userId":"u1","isNewUser":true,"isFirstLaunch":true,"installState":"newInstall"}"""

        const val OPEN_WITH_DEFERRED_LINK =
            """{"userId":"u1","isNewUser":true,"isFirstLaunch":true,"installState":"newInstall",""" +
                """"deepLink":{"path":"deferred/promo","uri":"https://sub.attriax.com/deferred/promo"}}"""

        const val OPEN_WITH_BOTH_REFERRERS =
            """{"userId":"u1","isNewUser":true,"isFirstLaunch":true,"installState":"reinstall",""" +
                """"originalInstallReferrer":{"source":"google","medium":"cpc","campaign":"spring_sale",""" +
                """"attributionType":"referrer","rawPlatformInstallReferrer":"utm_source=google",""" +
                """"installBeginTimestampSeconds":1234,"referrerClickTimestampSeconds":1200,""" +
                """"googlePlayInstantParam":false,"precision":0.92},""" +
                """"reinstallReferrer":{"source":"facebook","attributionType":"fingerprint","precision":0.5}}"""

        const val OPEN_WITH_ORIGINAL_ONLY =
            """{"userId":"u1","isNewUser":true,"isFirstLaunch":true,"installState":"newInstall",""" +
                """"originalInstallReferrer":{"source":"google","attributionType":"referrer","precision":0.8}}"""

        const val OPEN_WITH_MINIMAL_REFERRER =
            """{"userId":"u1","isNewUser":true,"isFirstLaunch":true,"installState":"newInstall",""" +
                """"originalInstallReferrer":{"rawPlatformInstallReferrer":"utm_source=x"}}"""
    }
}
