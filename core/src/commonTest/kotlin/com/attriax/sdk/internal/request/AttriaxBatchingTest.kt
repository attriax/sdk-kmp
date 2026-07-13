package com.attriax.sdk.internal.request

import com.attriax.sdk.internal.request.AttriaxBatching.QueuedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Field hoist/strip, limits + split, share identity incl. projectToken. */
class AttriaxBatchingTest {

    private fun event(token: String, deviceId: String?, source: String?, name: String = "e") =
        AttriaxRequestBuilders.buildEvent(
            projectToken = token,
            eventName = name,
            eventData = null,
            deviceId = deviceId,
            deviceIdSource = source,
            sessionId = null,
            sessionRelativeTimeMs = null,
            clientOccurredAtIso = "2026-01-01T00:00:00.000Z",
        )

    // ---- E5: hoist identity to envelope, strip from items ----

    @Test
    fun itemBodyStripsIdentityFields() {
        val item = AttriaxBatching.itemBody(event("t", "d1", "android_ssaid"))
        assertFalse(item.containsKey("projectToken"))
        assertFalse(item.containsKey("deviceId"))
        assertFalse(item.containsKey("deviceIdSource"))
        assertEquals("e", item["eventName"])
    }

    @Test
    fun batchBodyHoistsIdentityToEnvelope() {
        val items = listOf(
            QueuedItem("q1", event("t", "d1", "android_ssaid", "a")),
            QueuedItem("q2", event("t", "d1", "android_ssaid", "b")),
        )
        val body = AttriaxBatching.buildBatchBody(items)

        assertEquals("batch_q1", body["requestId"])
        assertEquals("t", body["projectToken"])
        assertEquals("d1", body["deviceId"])
        assertEquals("android_ssaid", body["deviceIdSource"])

        @Suppress("UNCHECKED_CAST")
        val batchItems = body["items"] as List<Map<String, Any?>>
        assertEquals(2, batchItems.size)
        assertEquals("event", batchItems[0]["kind"])
        @Suppress("UNCHECKED_CAST")
        val itemBody = batchItems[0]["body"] as Map<String, Any?>
        assertFalse(itemBody.containsKey("deviceId"))
    }

    @Test
    fun batchBodyOmitsDeviceIdSourceWhenNull() {
        val body = AttriaxBatching.buildBatchBody(listOf(QueuedItem("q1", event("t", "d1", null))))
        assertFalse(body.containsKey("deviceIdSource"))
    }

    // ---- Q6: share identity includes projectToken ----

    @Test
    fun requestsWithDifferentProjectTokenDoNotShare() {
        assertFalse(AttriaxBatching.canShare(event("t1", "d1", "s"), event("t2", "d1", "s")))
    }

    @Test
    fun requestsWithSameIdentityShare() {
        assertTrue(AttriaxBatching.canShare(event("t1", "d1", "s"), event("t1", "d1", "s")))
    }

    @Test
    fun requestsWithDifferentDeviceIdDoNotShare() {
        assertFalse(AttriaxBatching.canShare(event("t1", "d1", "s"), event("t1", "d2", "s")))
    }

    @Test
    fun eventWithoutDeviceIdIsNotBatchable() {
        assertFalse(event("t", null, null).isBatchable)
    }

    // ---- Q5: run collection stops at non-batchable / identity change / limits ----

    @Test
    fun collectRunStopsAtIdentityChange() {
        val queue = listOf(
            QueuedItem("q1", event("t", "d1", "s")),
            QueuedItem("q2", event("t", "d1", "s")),
            QueuedItem("q3", event("t", "d2", "s")), // different device → new group
        )
        val run = AttriaxBatching.collectSendableRun(queue, 0)
        assertEquals(2, run.size)
        assertEquals(listOf("q1", "q2"), run.map { it.id })
    }

    @Test
    fun collectRunHonoursItemLimit() {
        val queue = (0..150).map { QueuedItem("q$it", event("t", "d1", "s")) }
        val run = AttriaxBatching.collectSendableRun(queue, 0)
        assertEquals(AttriaxBatchLimits.MAX_ITEMS, run.size)
    }

    @Test
    fun collectRunSplitsByByteSize() {
        // Each event carries a big blob so a handful exceed 256 KiB.
        val bigData = mapOf("blob" to "x".repeat(50_000))
        fun bigEvent(i: Int) = AttriaxRequestBuilders.buildEvent(
            "t", "e$i", bigData, "d1", "s", null, null, "2026-01-01T00:00:00.000Z",
        )
        val queue = (0..20).map { QueuedItem("q$it", bigEvent(it)) }
        val run = AttriaxBatching.collectSendableRun(queue, 0)
        // Fewer than 6 items fit under 256 KiB (each ~50KB).
        assertTrue(run.size in 1..6, "run size was ${run.size}")
        val bytes = com.attriax.sdk.internal.json.Json.encodedByteSize(AttriaxBatching.buildBatchBody(run))
        assertTrue(bytes <= AttriaxBatchLimits.MAX_BODY_BYTES)
    }

    @Test
    fun nonBatchableHeadReturnsSingleRequest() {
        val open = AttriaxRequestBuilders.buildOpen(
            "t", com.attriax.sdk.internal.AttriaxContextSnapshot(
                packageName = "p", appVersion = null, appBuildNumber = null,
                deviceModel = null, deviceManufacturer = null, osVersion = "14",
                deviceTimezone = null, deviceLocale = null,
            ),
            "d1", "s", true, null, null,
        )
        val queue = listOf(QueuedItem("q1", open), QueuedItem("q2", event("t", "d1", "s")))
        val run = AttriaxBatching.collectSendableRun(queue, 0)
        assertEquals(1, run.size)
        assertEquals("q1", run.first().id)
    }
}
