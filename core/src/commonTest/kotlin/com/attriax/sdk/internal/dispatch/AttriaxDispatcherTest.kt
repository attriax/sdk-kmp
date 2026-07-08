package com.attriax.sdk.internal.dispatch

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxHttpException
import com.attriax.sdk.internal.AttriaxTransportException
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.queue.AttriaxQueueManager
import com.attriax.sdk.internal.queue.AttriaxQueuedRequest
import com.attriax.sdk.internal.request.AttriaxApiRequest
import com.attriax.sdk.internal.request.AttriaxEndpoints
import com.attriax.sdk.internal.request.AttriaxRequestBuilders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Integration coverage: the flush engine ties hoist + batching + retry together. */
class AttriaxDispatcherTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        override fun remove(key: String) { data.remove(key) }
    }

    /** Records paths posted; can be programmed to fail specific paths. */
    private class RecordingTransport(
        val failer: (path: String, body: String) -> Exception? = { _, _ -> null },
    ) : HttpClient {
        val posts = ArrayList<Pair<String, String>>()
        override fun post(path: String, body: String): HttpResponse {
            posts.add(path to body)
            failer(path, body)?.let { throw it }
            return HttpResponse(200, "{}")
        }
    }

    private val clock = AttriaxClock { 1_000L }

    private fun event(token: String, deviceId: String, name: String) =
        AttriaxRequestBuilders.buildEvent(token, name, null, deviceId, "android_ssaid", null, null, "2026-01-01T00:00:00.000Z")

    private fun enqueue(manager: AttriaxQueueManager, id: String, req: AttriaxApiRequest) =
        manager.enqueue(AttriaxQueuedRequest(id, req, createdAtMs = 0L))

    @Test
    fun batchesSharedIdentityEventsIntoOneBatchPost() {
        val store = MapStore()
        val queue = AttriaxQueueManager(store, 100)
        enqueue(queue, "q1", event("t", "d1", "a"))
        enqueue(queue, "q2", event("t", "d1", "b"))
        enqueue(queue, "q3", event("t", "d1", "c"))

        val transport = RecordingTransport()
        val delivered = AttriaxDispatcher(queue, transport, clock).flush()

        assertEquals(3, delivered)
        assertEquals(1, transport.posts.size)
        assertEquals(AttriaxEndpoints.BATCH, transport.posts.first().first)
        assertTrue(queue.readAll().isEmpty())
    }

    @Test
    fun appOpenIsSentFirstAsSingleRequest() {
        val store = MapStore()
        val queue = AttriaxQueueManager(store, 100)
        enqueue(queue, "e1", event("t", "d1", "a"))
        val open = AttriaxApiRequest(AttriaxApiRequest.KIND_OPEN, AttriaxEndpoints.OPEN, mapOf("projectToken" to "t"))
        enqueue(queue, "o1", open)

        val transport = RecordingTransport()
        AttriaxDispatcher(queue, transport, clock).flush()

        assertEquals(AttriaxEndpoints.OPEN, transport.posts.first().first)
    }

    @Test
    fun retryableFailureRequeuesWithIncrementedAttemptAndStops() {
        val store = MapStore()
        val queue = AttriaxQueueManager(store, 100)
        enqueue(queue, "q1", event("t", "d1", "a"))

        val transport = RecordingTransport { _, _ -> AttriaxHttpException(503, null) }
        val delivered = AttriaxDispatcher(queue, transport, clock).flush()

        assertEquals(0, delivered)
        val remaining = queue.readAll()
        assertEquals(1, remaining.size)
        assertEquals(1, remaining.first().attemptCount)
        assertEquals("http_503", remaining.first().lastErrorClass)
        assertTrue(remaining.first().nextRetryAtMs!! > 1_000L)
    }

    @Test
    fun nonRetryableFailureDropsRequest() {
        val store = MapStore()
        val queue = AttriaxQueueManager(store, 100)
        enqueue(queue, "q1", event("t", "d1", "a"))
        enqueue(queue, "q2", event("t", "d1", "b"))

        // 400 on the batch → non-retryable → binary split → each single also 400 → drop.
        val dropped = ArrayList<String>()
        val transport = RecordingTransport { _, _ -> AttriaxHttpException(400, null) }
        val dispatcher = AttriaxDispatcher(queue, transport, clock) { req, reason -> dropped.add("${req.id}:$reason") }
        dispatcher.flush()

        assertTrue(queue.readAll().isEmpty())
        assertEquals(2, dropped.size)
    }

    @Test
    fun binarySplitRetriesHalvesOnNonRetryableBatchFailure() {
        val store = MapStore()
        val queue = AttriaxQueueManager(store, 100)
        // 4 shared-identity events; the full batch 400s but singles succeed.
        (1..4).forEach { enqueue(queue, "q$it", event("t", "d1", "e$it")) }

        val transport = RecordingTransport { path, body ->
            // Fail only multi-item batch posts (those whose items array has >1 entry).
            if (path == AttriaxEndpoints.BATCH) {
                @Suppress("UNCHECKED_CAST")
                val items = (Json.decodeObject(body)["items"] as List<Any?>)
                if (items.size > 1) AttriaxHttpException(400, null) else null
            } else null
        }
        val delivered = AttriaxDispatcher(queue, transport, clock).flush()

        assertEquals(4, delivered)
        assertTrue(queue.readAll().isEmpty())
    }

    // ---- PARITY row S4: session keep-alive injection ----

    private fun sessionRequest(token: String, deviceId: String, sessionId: String, kind: String) =
        AttriaxRequestBuilders.buildSession(
            projectToken = token, kind = kind, sessionId = sessionId,
            deviceId = deviceId, deviceIdSource = "android_ssaid",
            clientOccurredAtIso = "2026-01-01T00:00:00.000Z",
        )

    private fun eventWithSession(token: String, deviceId: String, name: String, sessionId: String) =
        AttriaxRequestBuilders.buildEvent(
            token, name, null, deviceId, "android_ssaid", sessionId, 0L,
            "2026-01-01T00:00:00.000Z",
        )

    @Test
    fun appendsSyntheticKeepAliveToBatchCarryingLiveSessionEventAndReportsDelivery() {
        val store = MapStore()
        val queue = AttriaxQueueManager(store, 100)
        enqueue(queue, "q1", eventWithSession("t", "d1", "a", "sess-1"))
        enqueue(queue, "q2", eventWithSession("t", "d1", "b", "sess-1"))

        val delivered = ArrayList<Pair<String, Long>>()
        val transport = RecordingTransport()
        val dispatcher = AttriaxDispatcher(
            queue, transport, clock,
            buildSessionKeepAliveBatch = { group ->
                // The group carries a live-session event → inject a heartbeat keep-alive.
                if (group.any { it.request.body["sessionId"] == "sess-1" }) {
                    AttriaxBatchKeepAlive(
                        request = sessionRequest("t", "d1", "sess-1", "heartbeat"),
                        sessionId = "sess-1",
                        occurredAtMs = 4_242L,
                    )
                } else {
                    null
                }
            },
            onSessionKeepAliveDelivered = { sessionId, occurredAtMs ->
                delivered.add(sessionId to occurredAtMs)
            },
        )

        val count = dispatcher.flush()

        // Only the two persisted events count as delivered (the keep-alive is synthetic).
        assertEquals(2, count)
        // The batch payload carries all THREE items (2 events + 1 keep-alive).
        assertEquals(1, transport.posts.size)
        @Suppress("UNCHECKED_CAST")
        val items = Json.decodeObject(transport.posts.first().second)["items"] as List<Any?>
        assertEquals(3, items.size)
        // Delivery reported the keep-alive session + timestamp for the activity bump.
        assertEquals(listOf("sess-1" to 4_242L), delivered)
        // The synthetic keep-alive is NOT persisted (queue is empty after delivery).
        assertTrue(queue.readAll().isEmpty())
    }

    @Test
    fun doesNotInjectKeepAliveWhenBatchHasNoLiveSessionEvent() {
        val store = MapStore()
        val queue = AttriaxQueueManager(store, 100)
        enqueue(queue, "q1", event("t", "d1", "a"))
        enqueue(queue, "q2", event("t", "d1", "b"))

        val delivered = ArrayList<Pair<String, Long>>()
        val transport = RecordingTransport()
        val dispatcher = AttriaxDispatcher(
            queue, transport, clock,
            buildSessionKeepAliveBatch = { null },
            onSessionKeepAliveDelivered = { s, o -> delivered.add(s to o) },
        )

        dispatcher.flush()

        @Suppress("UNCHECKED_CAST")
        val items = Json.decodeObject(transport.posts.first().second)["items"] as List<Any?>
        assertEquals(2, items.size)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun terminalDropWhenAttemptsExceeded() {
        val store = MapStore()
        val queue = AttriaxQueueManager(store, 100)
        // Pre-seed a request already at 7 attempts; one more retryable failure → 8 → drop.
        queue.enqueue(
            AttriaxQueuedRequest("q1", event("t", "d1", "a"), createdAtMs = 0L, attemptCount = 7),
        )
        val dropped = ArrayList<String>()
        val transport = RecordingTransport { _, _ -> AttriaxTransportException() }
        AttriaxDispatcher(queue, transport, clock) { _, reason -> dropped.add(reason) }.flush()

        assertTrue(queue.readAll().isEmpty())
        assertEquals(listOf(AttriaxRetryPolicy.REASON_MAX_ATTEMPTS), dropped)
    }
}
