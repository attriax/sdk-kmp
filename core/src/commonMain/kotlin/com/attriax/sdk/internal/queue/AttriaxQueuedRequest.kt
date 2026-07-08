package com.attriax.sdk.internal.queue

import com.attriax.sdk.internal.request.AttriaxApiRequest

/**
 * A single persisted queued request (PARITY §7, row Q1).
 *
 * The persisted JSON shape is:
 * `{id, kind, body, createdAt, attemptCount, lastAttemptAt, lastErrorClass,
 *   lastHttpStatusCode, nextRetryAt}` — mirroring Flutter `AttriaxQueuedRequest`.
 * Timestamps are stored as epoch-millis longs here (the wire uses ISO-8601 in
 * the payloads themselves; queue bookkeeping only needs monotonic comparison).
 */
data class AttriaxQueuedRequest(
    val id: String,
    val request: AttriaxApiRequest,
    val createdAtMs: Long,
    val attemptCount: Int = 0,
    val lastAttemptAtMs: Long? = null,
    val lastErrorClass: String? = null,
    val lastHttpStatusCode: Int? = null,
    val nextRetryAtMs: Long? = null,
)
