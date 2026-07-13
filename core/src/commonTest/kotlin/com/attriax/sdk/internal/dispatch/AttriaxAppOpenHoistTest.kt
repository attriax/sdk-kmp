package com.attriax.sdk.internal.dispatch

import com.attriax.sdk.internal.queue.AttriaxQueuedRequest
import com.attriax.sdk.internal.request.AttriaxApiRequest
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals

/** app-open hoisted to the front of every flush, order otherwise stable. */
class AttriaxAppOpenHoistTest {

    private fun queued(id: String, kind: String) = AttriaxQueuedRequest(
        id = id,
        request = AttriaxApiRequest(kind, AttriaxEndpoints.EVENTS, emptyMap()),
        createdAtMs = 0L,
    )

    @Test
    fun hoistsOpenToFront() {
        val queue = listOf(
            queued("e1", AttriaxApiRequest.KIND_TRACK_EVENT),
            queued("e2", AttriaxApiRequest.KIND_TRACK_EVENT),
            queued("o1", AttriaxApiRequest.KIND_OPEN),
            queued("e3", AttriaxApiRequest.KIND_TRACK_EVENT),
        )
        val ordered = AttriaxAppOpenHoist.prioritize(queue)
        assertEquals(listOf("o1", "e1", "e2", "e3"), ordered.map { it.id })
    }

    @Test
    fun preservesRelativeOrderOfMultipleOpensAndOthers() {
        val queue = listOf(
            queued("e1", AttriaxApiRequest.KIND_TRACK_EVENT),
            queued("o1", AttriaxApiRequest.KIND_OPEN),
            queued("e2", AttriaxApiRequest.KIND_TRACK_EVENT),
            queued("o2", AttriaxApiRequest.KIND_OPEN),
        )
        val ordered = AttriaxAppOpenHoist.prioritize(queue)
        assertEquals(listOf("o1", "o2", "e1", "e2"), ordered.map { it.id })
    }

    @Test
    fun noOpWhenNoOpenPresent() {
        val queue = listOf(
            queued("e1", AttriaxApiRequest.KIND_TRACK_EVENT),
            queued("e2", AttriaxApiRequest.KIND_TRACK_EVENT),
        )
        assertEquals(listOf("e1", "e2"), AttriaxAppOpenHoist.prioritize(queue).map { it.id })
    }
}
