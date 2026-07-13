package com.attriax.sdk.internal.request

import com.attriax.sdk.internal.request.AttriaxApiRequest.Companion.FIELD_DEVICE_ID
import com.attriax.sdk.internal.request.AttriaxApiRequest.Companion.FIELD_DEVICE_ID_SOURCE
import com.attriax.sdk.internal.request.AttriaxApiRequest.Companion.FIELD_PROJECT_TOKEN

/** Batch limits: ≤100 items, ≤256 KiB encoded. */
object AttriaxBatchLimits {
    const val MAX_ITEMS = 100
    const val MAX_BODY_BYTES = 256 * 1024
}

/** Shared identity that consecutive batchable requests must agree on. */
data class AttriaxBatchIdentity(
    val projectToken: String,
    val deviceId: String,
    val deviceIdSource: String?,
)

/**
 * Pure batching field-placement + grouping helpers
 * (Flutter `batching_helpers.dart:63-80`).
 *
 * The batch-share identity INCLUDES `projectToken` (multi-project; sdk-js omits
 * it — we mirror Flutter). Each batch item strips
 * `projectToken`/`deviceId`/`deviceIdSource` and hoists them to the shared batch
 * envelope; the single-send path keeps them per-request.
 */
object AttriaxBatching {

    /** Extract the shared identity from a batchable request. */
    fun identityOf(request: AttriaxApiRequest): AttriaxBatchIdentity {
        require(request.isBatchable) { "not a batchable request: ${request.kind}" }
        val body = request.body
        val projectToken = body[FIELD_PROJECT_TOKEN] as? String
            ?: throw IllegalArgumentException("batchable request missing projectToken")
        val deviceId = body[FIELD_DEVICE_ID] as? String
            ?: throw IllegalArgumentException("batchable request missing deviceId")
        return AttriaxBatchIdentity(
            projectToken = projectToken,
            deviceId = deviceId,
            deviceIdSource = body[FIELD_DEVICE_ID_SOURCE] as? String,
        )
    }

    /** True when [left] and [right] are both batchable and share identity. */
    fun canShare(left: AttriaxApiRequest, right: AttriaxApiRequest): Boolean {
        if (!left.isBatchable || !right.isBatchable) return false
        return identityOf(left) == identityOf(right)
    }

    /** The per-item body with identity fields stripped (they live on the envelope). */
    fun itemBody(request: AttriaxApiRequest): Map<String, Any?> {
        require(request.isBatchable) { "not a batchable request: ${request.kind}" }
        val stripped = LinkedHashMap(request.body)
        stripped.remove(FIELD_PROJECT_TOKEN)
        stripped.remove(FIELD_DEVICE_ID)
        stripped.remove(FIELD_DEVICE_ID_SOURCE)
        return stripped
    }

    /** Stable batch requestId derived from the first queued request id. */
    fun batchRequestId(firstQueuedId: String): String = "batch_$firstQueuedId"

    /**
     * Build the `/api/sdk/v1/batch` envelope body from a group of queued
     * requests that share identity. Identity is hoisted; items carry stripped
     * bodies tagged with their batch kind name.
     */
    fun buildBatchBody(group: List<QueuedItem>): Map<String, Any?> {
        require(group.isNotEmpty()) { "cannot build an empty batch" }
        val first = group.first()
        val identity = identityOf(first.request)
        val body = LinkedHashMap<String, Any?>()
        body["requestId"] = batchRequestId(first.id)
        body[FIELD_PROJECT_TOKEN] = identity.projectToken
        body[FIELD_DEVICE_ID] = identity.deviceId
        if (identity.deviceIdSource != null) {
            body[FIELD_DEVICE_ID_SOURCE] = identity.deviceIdSource
        }
        body["items"] = group.map { item ->
            linkedMapOf<String, Any?>(
                "kind" to item.request.batchKindName,
                "body" to itemBody(item.request),
            )
        }
        return body
    }

    /** Minimal view of a queued request the batcher needs (id + request). */
    data class QueuedItem(val id: String, val request: AttriaxApiRequest)

    /**
     * Collect the maximal run of consecutive batchable requests starting at
     * [startIndex] that share identity AND fit within the item/byte limits.
     *
     * Mirrors Flutter `_collectSendableBatchRequests`: greedily extends the run,
     * stopping at the first non-batchable request, identity mismatch, or when
     * adding the next item would exceed a limit. If the run at [startIndex] is
     * not batchable at all, returns just that single request (single-send).
     */
    fun collectSendableRun(queue: List<QueuedItem>, startIndex: Int): List<QueuedItem> {
        val run = ArrayList<QueuedItem>()
        var index = startIndex
        while (index < queue.size) {
            val candidate = queue[index]
            if (!candidate.request.isBatchable) break
            val first = run.firstOrNull()
            if (first != null && !canShare(first.request, candidate.request)) break

            run.add(candidate)
            if (!fits(run)) {
                run.removeAt(run.size - 1)
                break
            }
            index++
        }
        return if (run.isNotEmpty()) run else listOf(queue[startIndex])
    }

    private fun fits(run: List<QueuedItem>): Boolean {
        if (run.isEmpty()) return false
        if (run.size > AttriaxBatchLimits.MAX_ITEMS) return false
        val bytes = com.attriax.sdk.internal.json.Json.encodedByteSize(buildBatchBody(run))
        return bytes <= AttriaxBatchLimits.MAX_BODY_BYTES
    }
}
