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
import com.attriax.sdk.internal.consent.AttriaxGdprConsentState
import com.attriax.sdk.internal.queue.AttriaxQueueManager
import com.attriax.sdk.internal.request.AttriaxApiRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine-level consent gating: the consent policy is
 * wired into the tracking enqueue path (anonymous capture / withhold / defer) and
 * the three-pass queue reconciliation runs on a consent state change. The consent
 * + flush executors are synchronous fakes, so the reconciliation + flush that a
 * consent decision triggers complete inline (deterministic on jvm AND native).
 */
class AttriaxConsentEngineTest {

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
     * A transport that echoes the consent upsert back faithfully (like the
     * backend), so the manager's applyRemoteStatus does not downgrade the just-set
     * local state. Non-consent posts get a generic 200.
     */
    private class OkTransport : HttpClient {
        val posts = mutableListOf<Pair<String, String>>()
        override fun post(path: String, body: String): HttpResponse {
            posts.add(path to body)
            if (path == "/api/sdk/v1/consent/gdpr" || path == "/api/sdk/v1/consent/gdpr/check") {
                val decoded = com.attriax.sdk.internal.json.Json.decodeObject(body)
                val state = decoded["state"] as? String ?: "unknown"
                val valuesJson = (decoded["values"] as? Map<*, *>)?.let {
                    ""","values":{"analytics":${it["analytics"]},"attribution":${it["attribution"]},"adEvents":${it["adEvents"]}}"""
                } ?: ""
                return HttpResponse(
                    200,
                    """{"state":"$state","needsConsent":false,"checkedAt":"2026-07-06T00:00:00.000Z"$valuesJson}""",
                )
            }
            return HttpResponse(200, "{}")
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

    private fun engine(store: MapStore, anonymous: Boolean, transport: OkTransport = OkTransport()): Attriax {
        store.putString("attriax.first_launch_completed", "false")
        val resolver = AttriaxDeviceIdentityResolver(FixedSources("SSAID-123"), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = AttriaxConfig(projectToken = "tok", gdprEnabled = true, anonymousTracking = anonymous),
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

    /**
     * Decoded event bodies actually POSTed — covering BOTH the single-send
     * `/events` path and the `/batch` envelope (whose event items carry stripped
     * identity, re-merged from the shared envelope so the deviceId is observable).
     */
    private fun sentEvents(transport: OkTransport): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        for ((path, raw) in transport.posts.toList()) {
            val decoded = com.attriax.sdk.internal.json.Json.decodeObject(raw)
            when (path) {
                "/api/sdk/v1/events" -> out.add(decoded)
                "/api/sdk/v1/batch" -> {
                    val sharedDeviceId = decoded["deviceId"]
                    val sharedSource = decoded["deviceIdSource"]
                    @Suppress("UNCHECKED_CAST")
                    val items = decoded["items"] as? List<Map<String, Any?>> ?: emptyList()
                    for (item in items) {
                        if (item["kind"] != "event") continue
                        @Suppress("UNCHECKED_CAST")
                        val body = LinkedHashMap(item["body"] as Map<String, Any?>)
                        if (sharedDeviceId != null) body["deviceId"] = sharedDeviceId
                        if (sharedSource != null) body["deviceIdSource"] = sharedSource
                        out.add(body)
                    }
                }
            }
        }
        return out
    }

    private fun queued(store: MapStore): List<AttriaxApiRequest> =
        AttriaxQueueManager(store, 500).readAll().map { it.request }

    private fun events(store: MapStore): List<AttriaxApiRequest> =
        queued(store).filter { it.kind == AttriaxApiRequest.KIND_TRACK_EVENT }

    // -------- anonymous capture while waiting --------

    @Test
    fun analyticsEventWhileWaitingWithAnonymousOnIsEnqueuedWithoutDeviceIdentity() {
        val store = MapStore()
        val engine = engine(store, anonymous = true)
        assertTrue(engine.isWaitingForGdprConsent)

        engine.tracking.recordEvent("level_up")

        val event = events(store).single()
        assertNull(event.body["deviceId"], "anonymous capture must strip deviceId")
        assertNull(event.body["deviceIdSource"])
        assertEquals("level_up", event.body["eventName"])
    }

    @Test
    fun userIdentifyWhileWaitingIsWithheld() {
        val store = MapStore()
        val engine = engine(store, anonymous = true)
        engine.tracking.setUser("u1")
        assertTrue(
            queued(store).none { it.kind == AttriaxApiRequest.KIND_USER },
            "attribution-linked identify must be withheld while waiting",
        )
    }

    // -------- three-pass reconciliation on resolution --------

    @Test
    fun grantingAnalyticsIdentifiesAPreviouslyAnonymousQueuedEvent() {
        val store = MapStore()
        val transport = OkTransport()
        val engine = engine(store, anonymous = true, transport = transport)
        engine.tracking.recordEvent("level_up") // enqueued anonymous (no deviceId), not flushed
        assertNull(events(store).single().body["deviceId"])

        // Resolve consent to analytics-granted → PASS 1 attaches device identity,
        // then the event flushes. The SENT body must carry the re-attached identity.
        engine.consent.gdpr.setConsent(analytics = true, attribution = false, adEvents = false)

        assertTrue(sentEvents(transport).any { it["eventName"] == "level_up" })
        val sent = sentEvents(transport).single { it["eventName"] == "level_up" }
        assertEquals("SSAID-123", sent["deviceId"])
        assertEquals("android_ssaid", sent["deviceIdSource"])
    }

    @Test
    fun denyingAnalyticsWithAnonymousOffDiscardsTheQueuedAnalyticsEvent() {
        val store = MapStore()
        val transport = OkTransport()
        val engine = engine(store, anonymous = true, transport = transport)
        engine.tracking.recordEvent("level_up")
        assertEquals(1, events(store).size)

        // Turn anonymous OFF, then resolve with analytics DECLINED → PASS 3 drops it.
        engine.anonymousTrackingEnabled = false
        engine.consent.gdpr.setConsent(analytics = false, attribution = false, adEvents = false)

        assertTrue(events(store).none { it.body["eventName"] == "level_up" })
        // The event was DISCARDED (gdpr_consent_denied), never sent to the network.
        assertTrue(sentEvents(transport).none { it["eventName"] == "level_up" })
    }

    @Test
    fun requestDataErasurePostsDeviceIdToTheEraseEndpointAndResetsTheSdk() {
        val store = MapStore()
        val transport = OkTransport()
        val engine = engine(store, anonymous = true, transport = transport)
        assertTrue(engine.isInitialized)

        engine.consent.gdpr.requestDataErasure()

        val (path, body) = transport.posts.single { it.first == "/api/sdk/v1/privacy/gdpr/erase" }
        val decoded = com.attriax.sdk.internal.json.Json.decodeObject(body)
        assertEquals("/api/sdk/v1/privacy/gdpr/erase", path)
        assertEquals("tok", decoded["projectToken"])
        // The erase endpoint IS the one consent-family endpoint that carries the
        // deviceId (check/upsert never do).
        assertEquals("SSAID-123", decoded["deviceId"])
        // On success the SDK returns to pre-init.
        assertFalse(engine.isInitialized)
    }

    @Test
    fun setNotRequiredResolvesStateAndStopsWaiting() {
        val store = MapStore()
        val transport = OkTransport()
        val engine = engine(store, anonymous = true, transport = transport)
        engine.consent.gdpr.setNotRequired()
        assertEquals(AttriaxGdprConsentState.NOT_REQUIRED, engine.consent.gdpr.state)
        assertFalse(engine.consent.gdpr.isWaitingForConsent)

        // Now identified tracking is allowed: a fresh event carries the identity.
        engine.tracking.recordEvent("post_consent", flushImmediately = true)
        assertTrue(sentEvents(transport).any { it["eventName"] == "post_consent" })
        val sent = sentEvents(transport).single { it["eventName"] == "post_consent" }
        assertEquals("SSAID-123", sent["deviceId"])
    }
}
