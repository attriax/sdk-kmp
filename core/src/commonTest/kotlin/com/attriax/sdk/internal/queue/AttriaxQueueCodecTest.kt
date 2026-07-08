package com.attriax.sdk.internal.queue

import com.attriax.sdk.internal.request.AttriaxApiRequest
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PARITY rows Q1 (serialize/corruption) and FR1 (legacy field/kind normalization). */
class AttriaxQueueCodecTest {

    private fun event(id: String) = AttriaxQueuedRequest(
        id = id,
        request = AttriaxApiRequest(
            AttriaxApiRequest.KIND_TRACK_EVENT,
            AttriaxEndpoints.EVENTS,
            linkedMapOf("projectToken" to "t", "eventName" to "e", "deviceId" to "d"),
        ),
        createdAtMs = 123L,
        attemptCount = 2,
        lastErrorClass = "http_500",
        lastHttpStatusCode = 500,
        nextRetryAtMs = 456L,
    )

    // ---- Q1: roundtrip preserves attemptCount / nextRetryAt ----

    @Test
    fun roundtripsPreservingRetryBookkeeping() {
        val encoded = AttriaxQueueCodec.encode(listOf(event("q1")))
        val decoded = AttriaxQueueCodec.decode(encoded).queue
        assertEquals(1, decoded.size)
        val r = decoded.first()
        assertEquals("q1", r.id)
        assertEquals(2, r.attemptCount)
        assertEquals(123L, r.createdAtMs)
        assertEquals(456L, r.nextRetryAtMs)
        assertEquals("http_500", r.lastErrorClass)
        assertEquals(500, r.lastHttpStatusCode)
        assertEquals("t", r.request.body["projectToken"])
    }

    // ---- AT3: restored-from-queue open preserves the attestation envelope ----

    @Test
    fun openPreservesAttestationEnvelopeAcrossRestore() {
        val open = AttriaxQueuedRequest(
            id = "open-1",
            request = AttriaxApiRequest(
                AttriaxApiRequest.KIND_OPEN,
                AttriaxEndpoints.OPEN,
                linkedMapOf(
                    "projectToken" to "t",
                    "deviceId" to "d",
                    "attestation" to linkedMapOf(
                        "provider" to "play_integrity",
                        "token" to "integrity_token",
                        "nonce" to "server_nonce",
                    ),
                ),
            ),
            createdAtMs = 1L,
        )

        val decoded = AttriaxQueueCodec.decode(AttriaxQueueCodec.encode(listOf(open))).queue
        assertEquals(1, decoded.size)
        val restored = decoded.first().request
        assertEquals(AttriaxApiRequest.KIND_OPEN, restored.kind)
        @Suppress("UNCHECKED_CAST")
        val envelope = restored.body["attestation"] as Map<String, Any?>
        assertEquals("play_integrity", envelope["provider"])
        assertEquals("integrity_token", envelope["token"])
        assertEquals("server_nonce", envelope["nonce"])
    }

    @Test
    fun emptyPayloadDecodesToEmptyQueue() {
        assertTrue(AttriaxQueueCodec.decode(null).queue.isEmpty())
        assertTrue(AttriaxQueueCodec.decode("").queue.isEmpty())
    }

    @Test
    fun invalidWholePayloadIsCleared() {
        val result = AttriaxQueueCodec.decode("{not an array")
        assertTrue(result.clearedWholePayload)
        assertTrue(result.queue.isEmpty())
    }

    @Test
    fun invalidIndividualEntriesAreDropped() {
        // Second entry lacks required "id".
        val payload = """[{"id":"q1","kind":"trackEvent","body":{"projectToken":"t"},"createdAt":1},{"kind":"trackEvent","body":{}}]"""
        val result = AttriaxQueueCodec.decode(payload)
        assertEquals(1, result.queue.size)
        assertEquals(1, result.droppedEntryCount)
        assertEquals("q1", result.queue.first().id)
    }

    // ---- FR1: legacy normalization ----

    @Test
    fun legacyAppTokenRenamedToProjectToken() {
        val (kind, body) = AttriaxQueueCodec.normalize(
            "trackEvent",
            linkedMapOf("appToken" to "legacy-tok", "eventName" to "e"),
        )
        assertEquals(AttriaxApiRequest.KIND_TRACK_EVENT, kind)
        assertEquals("legacy-tok", body["projectToken"])
        assertNull(body["appToken"])
    }

    @Test
    fun projectTokenWinsWhenBothPresent() {
        val (_, body) = AttriaxQueueCodec.normalize(
            "trackEvent",
            linkedMapOf("projectToken" to "new-tok", "appToken" to "legacy-tok"),
        )
        assertEquals("new-tok", body["projectToken"])
        // appToken untouched (only migrated when projectToken absent), matching Flutter.
        assertEquals("legacy-tok", body["appToken"])
    }

    @Test
    fun identifyKindAliasedToUser() {
        val (kind, _) = AttriaxQueueCodec.normalize(AttriaxApiRequest.LEGACY_KIND_IDENTIFY, emptyMap())
        assertEquals(AttriaxApiRequest.KIND_USER, kind)
    }

    @Test
    fun legacyIdentifyEntryRestoresAsUserThroughDecode() {
        val payload = """[{"id":"q1","kind":"identify","body":{"appToken":"legacy","userId":"u"},"createdAt":1}]"""
        val decoded = AttriaxQueueCodec.decode(payload).queue
        assertEquals(1, decoded.size)
        assertEquals(AttriaxApiRequest.KIND_USER, decoded.first().request.kind)
        assertEquals(AttriaxEndpoints.USERS, decoded.first().request.path)
        assertEquals("legacy", decoded.first().request.body["projectToken"])
    }
}
