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
import com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerCoordinator
import com.attriax.sdk.internal.referrer.AttriaxReferrerCoordinator
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Privacy-correctness parity for the referrer query API when attribution consent is
 * revoked (referrer / GDPR). Mirrors the Flutter reference
 * `attriax_runtime.dart:976`, which calls
 * `AttriaxReferrerManager.prepareForDeniedAttributionState()` in the else branch of
 * the active runtime state when attribution is denied: the persisted original /
 * reinstall attribution records AND the raw Play install referrer must be wiped, so
 * no install/reinstall attribution stays readable through the getters. Conversely, a
 * consent change that keeps attribution allowed must NOT clear anything.
 *
 * The consent + flush executors are synchronous fakes, so the app-open flush (which
 * persists the attribution records) and the reconciliation that a consent decision
 * triggers both complete inline — deterministic on jvm AND native.
 */
class AttriaxReferrerConsentEngineTest {

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

    /**
     * Returns the app-open body carrying both attribution records, and echoes the
     * consent upsert faithfully (like the backend) so applyRemoteStatus does not
     * downgrade the just-set local state.
     */
    private class ReferrerConsentTransport : HttpClient {
        override fun post(path: String, body: String): HttpResponse = when (path) {
            "/api/sdk/v1/open" -> HttpResponse(200, OPEN_WITH_BOTH_REFERRERS)
            "/api/sdk/v1/consent/gdpr", "/api/sdk/v1/consent/gdpr/check" -> {
                val decoded = com.attriax.sdk.internal.json.Json.decodeObject(body)
                val state = decoded["state"] as? String ?: "unknown"
                val valuesJson = (decoded["values"] as? Map<*, *>)?.let {
                    ""","values":{"analytics":${it["analytics"]},"attribution":${it["attribution"]},"adEvents":${it["adEvents"]}}"""
                } ?: ""
                HttpResponse(
                    200,
                    """{"state":"$state","needsConsent":false,"checkedAt":"2026-07-06T00:00:00.000Z"$valuesJson}""",
                )
            }
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

    private fun engine(store: MapStore): Attriax {
        store.putString("attriax.first_launch_completed", "false")
        // Seed the raw Play install referrer (Flutter's `clearStoredReferrer` target).
        store.putString(
            AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER,
            "utm_source=google&utm_medium=cpc",
        )
        val resolver = AttriaxDeviceIdentityResolver(FixedSources("SSAID-123"), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = AttriaxConfig(projectToken = "tok", gdprEnabled = true),
            store = store,
            transport = ReferrerConsentTransport(),
            connectivity = NoopConnectivity(),
            context = context,
            deviceIdentityStore = identityStore,
            clock = AttriaxClock { 1_000L },
            flushExecutor = AttriaxTestBackgroundExecutor(),
            consentExecutor = AttriaxTestBackgroundExecutor(),
        ).also { it.init() }
    }

    /**
     * Grant attribution so the buffered app-open flushes and its attribution records
     * (original + reinstall) are persisted, then assert all three referrer sources
     * are readable — the setup shared by both tests.
     */
    private fun grantAttributionAndCaptureReferrers(store: MapStore): Attriax {
        val sdk = engine(store)
        // Attribution granted → the app-open buffered at init flushes + delivers,
        // persisting the original/reinstall attribution records (referrer).
        sdk.consent.gdpr.setConsent(analytics = true, attribution = true, adEvents = false)

        assertNotNull(sdk.referrer.getOriginalInstallReferrer(), "original referrer must be captured")
        assertNotNull(sdk.referrer.getReinstallReferrer(), "reinstall referrer must be captured")
        assertNotNull(sdk.referrer.getRawInstallReferrer(), "raw install referrer must be present")
        return sdk
    }

    @Test
    fun denyingAttributionClearsPersistedInstallAndReinstallReferrerState() {
        val store = MapStore()
        val sdk = grantAttributionAndCaptureReferrers(store)

        // Revoke attribution (analytics still granted, so the SDK stays active and not
        // waiting) → the consent-change path runs prepareForDeniedAttributionState().
        sdk.consent.gdpr.setConsent(analytics = true, attribution = false, adEvents = false)

        assertNull(sdk.referrer.getOriginalInstallReferrer(), "original referrer must be wiped on attribution denial")
        assertNull(sdk.referrer.getReinstallReferrer(), "reinstall referrer must be wiped on attribution denial")
        assertNull(sdk.referrer.getRawInstallReferrer(), "raw install referrer must be wiped on attribution denial")

        // The persisted keys themselves are gone, not merely masked by the getters.
        assertNull(store.data[AttriaxReferrerCoordinator.KEY_ORIGINAL_INSTALL_REFERRER_DETAILS])
        assertNull(store.data[AttriaxReferrerCoordinator.KEY_REINSTALL_REFERRER_DETAILS])
        assertNull(store.data[AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER])
    }

    @Test
    fun consentChangeWithAttributionStillAllowedDoesNotClearReferrerState() {
        val store = MapStore()
        val sdk = grantAttributionAndCaptureReferrers(store)

        // A consent change that KEEPS attribution allowed (only toggling adEvents) must
        // not wipe any referrer state — no premature clearing.
        sdk.consent.gdpr.setConsent(analytics = true, attribution = true, adEvents = true)

        assertNotNull(sdk.referrer.getOriginalInstallReferrer(), "original referrer must survive an attribution-allowed change")
        assertNotNull(sdk.referrer.getReinstallReferrer(), "reinstall referrer must survive an attribution-allowed change")
        assertNotNull(sdk.referrer.getRawInstallReferrer(), "raw install referrer must survive an attribution-allowed change")

        assertTrue(store.data.containsKey(AttriaxReferrerCoordinator.KEY_ORIGINAL_INSTALL_REFERRER_DETAILS))
        assertTrue(store.data.containsKey(AttriaxReferrerCoordinator.KEY_REINSTALL_REFERRER_DETAILS))
        assertTrue(store.data.containsKey(AttriaxInstallReferrerCoordinator.KEY_INSTALL_REFERRER))
    }

    private companion object {
        const val OPEN_WITH_BOTH_REFERRERS =
            """{"userId":"u1","isNewUser":true,"isFirstLaunch":true,"installState":"reinstall",""" +
                """"originalInstallReferrer":{"source":"google","medium":"cpc","campaign":"spring_sale",""" +
                """"attributionType":"referrer","rawPlatformInstallReferrer":"utm_source=google",""" +
                """"installBeginTimestampSeconds":1234,"referrerClickTimestampSeconds":1200,""" +
                """"googlePlayInstantParam":false,"precision":0.92},""" +
                """"reinstallReferrer":{"source":"facebook","attributionType":"fingerprint","precision":0.5}}"""
    }
}
