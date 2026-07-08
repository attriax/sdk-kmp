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
import com.attriax.sdk.internal.queue.AttriaxQueueManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end lowering coverage (PARITY rows E1/E2/E3/E6 + identify) driven
 * through the real [Attriax] engine with fakes. The engine's flush + consent
 * executors are synchronous fakes so every enqueue/flush completes inline and the
 * queued request bodies are inspected deterministically via a second
 * [AttriaxQueueManager] reading the same store.
 */
class AttriaxTrackingTest {

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

    /** Never fails; records posts for the flush-default assertions. */
    private class RecordingTransport : HttpClient {
        val posts = mutableListOf<Pair<String, String>>()
        override fun post(path: String, body: String): HttpResponse {
            posts.add(path to body)
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

    private fun newEngine(
        store: MapStore = MapStore(),
        transport: RecordingTransport = RecordingTransport(),
        firstLaunch: Boolean = false,
    ): Attriax {
        // Pre-mark first-launch completed unless we explicitly want the eager
        // first-launch flush, so flush-immediate defaults are observable in isolation.
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

    /** The single non-open queued body (init enqueues one app-open first). */
    private fun lastQueuedBody(store: MapStore): Map<String, Any?> {
        val queue = AttriaxQueueManager(store, 500).readAll()
        val nonOpen = queue.map { it.request }.filter { !it.isAppOpen }
        assertTrue(nonOpen.isNotEmpty(), "expected a queued non-open request")
        return nonOpen.last().body
    }

    @Suppress("UNCHECKED_CAST")
    private fun eventData(body: Map<String, Any?>): Map<String, Any?> =
        body["eventData"] as Map<String, Any?>

    // -------- E1: reserved event names + param keys --------

    @Test
    fun purchaseLowersToReservedNameAndParams() {
        val store = MapStore()
        val engine = newEngine(store)
        engine.tracking.recordPurchase(
            revenue = 9.99,
            currency = "USD",
            productId = "p1",
            transactionId = "tx1",
            quantity = 2,
            // Lowering test — pin flush off so the queue entry is inspected before
            // a flush drains it (flush timing is covered by the flush-default tests).
            flushImmediately = false,
        )
        val body = lastQueuedBody(store)
        assertEquals("purchase", body["eventName"])
        val data = eventData(body)
        assertEquals(9.99, data["revenue"])
        assertEquals("USD", data["currency"])
        assertEquals("p1", data["productId"])
        assertEquals("tx1", data["transactionId"])
        assertEquals(2, (data["quantity"] as Number).toInt())
    }

    @Test
    fun adRevenueLowersToReservedName() {
        val store = MapStore()
        newEngine(store).tracking.recordAdRevenue(revenue = 0.02, currency = "USD", adNetwork = "admob", flushImmediately = false)
        val body = lastQueuedBody(store)
        assertEquals("ad_revenue", body["eventName"])
        assertEquals("admob", eventData(body)["adNetwork"])
    }

    @Test
    fun adEventLowersToReservedNamePerType() {
        val store = MapStore()
        newEngine(store).tracking.recordAdEvent(AttriaxAdEventType.IMPRESSION, adNetwork = "admob", flushImmediately = false)
        val body = lastQueuedBody(store)
        assertEquals("ad_impression", body["eventName"])
    }

    // -------- E2: refund negation + revenueType --------

    @Test
    fun refundNegatesRevenueAndTagsRevenueType() {
        val store = MapStore()
        newEngine(store).tracking.recordRefund(revenue = 9.99, currency = "USD", flushImmediately = false)
        val body = lastQueuedBody(store)
        assertEquals("refund", body["eventName"])
        val data = eventData(body)
        assertEquals(-9.99, data["revenue"])
        assertEquals("refund", data["revenueType"])
        assertEquals("USD", data["currency"])
    }

    // -------- E3: currency validation --------

    @Test
    fun invalidCurrencyForcesZeroUsd() {
        val store = MapStore()
        newEngine(store).tracking.recordPurchase(revenue = 42.0, currency = "US", flushImmediately = false)
        val data = eventData(lastQueuedBody(store))
        // Integral revenue survives the queue JSON round-trip as a Long (0), so
        // compare numerically rather than by boxed type.
        assertEquals(0.0, (data["revenue"] as Number).toDouble(), 0.0)
        assertEquals("USD", data["currency"])
    }

    @Test
    fun validLowercaseCurrencyIsUppercased() {
        val store = MapStore()
        newEngine(store).tracking.recordPurchase(revenue = 5.0, currency = "eur", flushImmediately = false)
        val data = eventData(lastQueuedBody(store))
        assertEquals(5.0, (data["revenue"] as Number).toDouble(), 0.0)
        assertEquals("EUR", data["currency"])
    }

    // -------- E6: notification source inference + payload metadata --------

    @Test
    fun notificationInfersFcmFromGooglePayload() {
        val store = MapStore()
        newEngine(store).tracking.recordNotificationOpened(
            notificationId = "n1",
            payload = mapOf("google.message_id" to "1"),
        )
        val body = lastQueuedBody(store)
        assertEquals("opened", body["type"])
        assertEquals("fcm", body["source"])
        @Suppress("UNCHECKED_CAST")
        val metadata = body["metadata"] as Map<String, Any?>
        assertEquals(mapOf("google.message_id" to "1"), metadata["payload"])
    }

    @Test
    fun notificationInfersApnsFromApsPayload() {
        val store = MapStore()
        newEngine(store).tracking.recordNotificationReceived(
            notificationId = "n2",
            payload = mapOf("aps" to mapOf("alert" to "hi")),
        )
        assertEquals("apns", lastQueuedBody(store)["source"])
    }

    @Test
    fun explicitNotificationSourceWins() {
        val store = MapStore()
        newEngine(store).tracking.recordNotificationDismissed(
            notificationId = "n3",
            source = AttriaxNotificationEventSource.OTHER,
            payload = mapOf("aps" to "x"),
        )
        assertEquals("other", lastQueuedBody(store)["source"])
    }

    // -------- identify: userId → externalUserId --------

    @Test
    fun setUserMapsUserIdToExternalUserId() {
        val store = MapStore()
        newEngine(store).tracking.setUser("user-42", userName = "Ada")
        val body = lastQueuedBody(store)
        assertEquals("user-42", body["externalUserId"])
        assertEquals("Ada", body["externalUserName"])
        assertEquals("SSAID-123", body["deviceId"])
        assertFalse(body.containsKey("userId"))
        assertFalse(body.containsKey("clearExternalUser"))
    }

    @Test
    fun setUserNullClearsExternalUser() {
        val store = MapStore()
        newEngine(store).tracking.setUser(null)
        val body = lastQueuedBody(store)
        assertEquals(true, body["clearExternalUser"])
        assertFalse(body.containsKey("externalUserId"))
    }

    @Test
    fun clearUserPropertiesWithNamesEmitsKeys() {
        val store = MapStore()
        newEngine(store).tracking.clearUserProperties(propertyNames = listOf("plan", "  "))
        val body = lastQueuedBody(store)
        assertEquals(listOf("plan"), body["clearPropertyKeys"])
        assertFalse(body.containsKey("clearAllProperties"))
    }

    @Test
    fun clearAllUserPropertiesWhenNoNames() {
        val store = MapStore()
        newEngine(store).tracking.clearUserProperties()
        val body = lastQueuedBody(store)
        assertEquals(true, body["clearAllProperties"])
        assertFalse(body.containsKey("clearPropertyKeys"))
    }

    // -------- flushImmediately defaults per kind --------

    @Test
    fun purchaseFlushesImmediatelyByDefault() {
        val transport = RecordingTransport()
        val engine = newEngine(transport = transport)
        engine.tracking.recordPurchase(revenue = 1.0, currency = "USD")
        assertTrue(transport.posts.isNotEmpty(), "purchase should trigger a flush")
    }

    @Test
    fun plainEventDoesNotFlushImmediatelyByDefault() {
        val transport = RecordingTransport()
        // The init app-open flushed synchronously; clear it, then a plain event must
        // NOT flush.
        val engine = newEngine(transport = transport)
        transport.posts.clear()
        engine.tracking.recordEvent("custom")
        assertTrue(transport.posts.isEmpty(), "plain event should not flush by default")
    }

    @Test
    fun validateReceiptIsDirectAndWorksWhenDisabled() {
        val store = MapStore()
        val transport = RecordingTransport()
        val engine = newEngine(store, transport)
        engine.enabled = false // tracking disabled must not block a direct receipt call
        val result = engine.validateReceipt(receipt = "R", provider = "play")
        // Direct call hit the transport synchronously.
        val receiptPost = transport.posts.firstOrNull { it.first.contains("receipts/validate") }
        assertTrue(receiptPost != null, "receipt validate should post directly")
        // Empty response object → lenient typed result: rejected, empty validationId + receipt.
        assertEquals(AttriaxRevenueReceiptValidationStatus.REJECTED, result.status)
        assertEquals("", result.validationId)
        assertTrue(result.publicReceipt.isEmpty())
        // It bypasses the queue (no receipt entry persisted).
        val queued = AttriaxQueueManager(store, 500).readAll()
        assertTrue(queued.none { it.request.path.contains("receipts/validate") })
    }
}
