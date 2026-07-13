package com.attriax.sdk.internal.dispatch

import com.attriax.sdk.internal.request.AttriaxApiRequest

/**
 * A synthetic session keep-alive appended to a batch that carries a live-session
 * event. The [request] MUST be a batchable session request
 * that shares identity with the batch group. It is appended to the transport
 * payload only (never persisted in the queue); on successful batch delivery the
 * dispatcher reports `(sessionId, occurredAtMs)` so the session manager can bump
 * last-activity. Mirrors Flutter's `_BatchKeepAliveRequest` + the synthetic
 * `keepalive_<sessionId>_<micros>` queued request.
 */
data class AttriaxBatchKeepAlive(
    val request: AttriaxApiRequest,
    val sessionId: String,
    val occurredAtMs: Long,
) {
    /** Stable synthetic id (id space is disjoint from persisted queued ids). */
    val syntheticId: String get() = "keepalive_${sessionId}_$occurredAtMs"
}
