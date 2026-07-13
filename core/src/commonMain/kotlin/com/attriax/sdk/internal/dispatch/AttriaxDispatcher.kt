package com.attriax.sdk.internal.dispatch

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxHttpException
import com.attriax.sdk.internal.AttriaxTimeoutException
import com.attriax.sdk.internal.AttriaxTransportException
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.queue.AttriaxQueueManager
import com.attriax.sdk.internal.queue.AttriaxQueuedRequest
import com.attriax.sdk.internal.request.AttriaxBatching
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The flush engine. Combines:
 * - app-open-first hoist,
 * - consecutive-identity batch grouping + limits + binary split,
 *  - single-send fallback,
 * - retry marking / backoff / terminal drop,
 *  - single-flight flush.
 *
 * Transport failures are classified into [AttriaxFailure] so the retry policy
 * stays pure; the dispatcher only owns orchestration + persistence.
 */
class AttriaxDispatcher(
    private val queue: AttriaxQueueManager,
    private val transport: HttpClient,
    private val clock: AttriaxClock,
    /**
     * Notified with the (envelope-unwrapped) response when a SINGLE-SEND request is
     * delivered (2xx). Used by the app-open handler to recover the deferred deep
     * link and by deep-link resolves to emit the resolved event. Not
     * invoked for batched items (open + deep-link resolve are both non-batchable).
     */
    private val onDelivered: ((AttriaxQueuedRequest, com.attriax.sdk.internal.HttpResponse) -> Unit)? = null,
    /**
     * Session keep-alive injection. Given the group of queued
     * requests forming a batch, returns a synthetic session keep-alive to APPEND to
     * that batch (or null to inject none). It is appended to the transport payload
     * only — it is NOT persisted in the queue, so a re-queue on failure never
     * accumulates keep-alives. Mirrors Flutter `buildSessionKeepAliveBatchRequest`.
     */
    private val buildSessionKeepAliveBatch: ((List<AttriaxQueuedRequest>) -> AttriaxBatchKeepAlive?)? = null,
    /**
     * Invoked with `(sessionId, occurredAtMs)` after a batch carrying an injected
     * keep-alive is delivered (2xx), so the session manager can bump last-activity.
     * Mirrors Flutter `onSessionKeepAliveDelivered`.
     */
    private val onSessionKeepAliveDelivered: ((sessionId: String, occurredAtMs: Long) -> Unit)? = null,
    /**
     * Notified when a request is permanently dropped (non-retryable or terminal).
     * Kept LAST so existing trailing-lambda call sites bind to it.
     */
    private val onDropped: ((AttriaxQueuedRequest, reason: String) -> Unit)? = null,
) {
    private val flushLock = SynchronizedObject()

    /**
     * Flush the queue once. Single-flight: concurrent callers are serialized.
     * Returns the number of requests successfully delivered.
     */
    fun flush(): Int {
        synchronized(flushLock) {
            val ordered = AttriaxAppOpenHoist.prioritize(queue.readAll())
            if (ordered.isEmpty()) return 0

            // Ids present at flush start; anything enqueued DURING the flush must be
            // preserved on write-back so a concurrent enqueue is not clobbered.
            val snapshotIds = ordered.mapTo(HashSet()) { it.id }
            val remaining = ArrayList(ordered)
            var delivered = 0
            var index = 0

            while (index < remaining.size) {
                // Skip requests still inside their retry window.
                val head = remaining[index]
                if (isWaitingForRetry(head)) {
                    index++
                    continue
                }

                val items = AttriaxBatching.collectSendableRun(
                    remaining.map { AttriaxBatching.QueuedItem(it.id, it.request) },
                    index,
                )
                val group = remaining.subList(index, index + items.size).toList()

                val outcome = if (group.size == 1 && !group.first().request.isBatchable) {
                    sendSingle(group.first())
                } else {
                    sendBatch(group)
                }

                // Replace the group in `remaining` with whatever must be re-queued.
                repeat(group.size) { remaining.removeAt(index) }
                remaining.addAll(index, outcome.reQueued)
                delivered += outcome.deliveredCount

                if (outcome.stop) {
                    // A retryable failure halts this flush pass; persist and bail.
                    break
                }
                index += outcome.reQueued.size
            }

            queue.writeAllPreservingNew(remaining, snapshotIds)
            return delivered
        }
    }

    private data class Outcome(
        val reQueued: List<AttriaxQueuedRequest>,
        val deliveredCount: Int,
        val stop: Boolean,
    )

    private fun sendSingle(queued: AttriaxQueuedRequest): Outcome {
        return try {
            val response = transport.post(queued.request.path, Json.encode(queued.request.body))
            onDelivered?.invoke(queued, response)
            Outcome(emptyList(), 1, stop = false)
        } catch (e: Exception) {
            val failure = classify(e) ?: run {
                // Non-transport exception — treat as a hard drop to avoid a poison loop.
                onDropped?.invoke(queued, "unexpected_error")
                return Outcome(emptyList(), 0, stop = false)
            }
            handleSingleFailure(queued, failure)
        }
    }

    private fun handleSingleFailure(queued: AttriaxQueuedRequest, failure: AttriaxFailure): Outcome {
        val attemptedAt = clock.nowMs()
        if (AttriaxRetryPolicy.isRetryable(failure)) {
            val marked = markForRetry(queued, failure, attemptedAt)
            if (dropIfTerminal(marked, attemptedAt)) {
                return Outcome(emptyList(), 0, stop = false)
            }
            // Retryable failure halts the flush (server likely unhealthy).
            return Outcome(listOf(marked), 0, stop = true)
        }

        // Non-retryable (other 4xx) → drop.
        onDropped?.invoke(queued, "non_retryable_${AttriaxRetryPolicy.errorClass(failure)}")
        return Outcome(emptyList(), 0, stop = false)
    }

    private fun sendBatch(group: List<AttriaxQueuedRequest>): Outcome {
        // Row S4: append a synthetic session keep-alive to the TRANSPORT payload only
        // (never persisted), when the group carries an event for the live session.
        val keepAlive = buildSessionKeepAliveBatch?.invoke(group)
        val transportItems = if (keepAlive != null) {
            group.map { AttriaxBatching.QueuedItem(it.id, it.request) } +
                AttriaxBatching.QueuedItem(keepAlive.syntheticId, keepAlive.request)
        } else {
            group.map { AttriaxBatching.QueuedItem(it.id, it.request) }
        }
        val batchBody = AttriaxBatching.buildBatchBody(transportItems)
        return try {
            transport.post(AttriaxEndpoints.BATCH, Json.encode(batchBody))
            keepAlive?.let { onSessionKeepAliveDelivered?.invoke(it.sessionId, it.occurredAtMs) }
            Outcome(emptyList(), group.size, stop = false)
        } catch (e: Exception) {
            val failure = classify(e)
            if (failure != null && AttriaxRetryPolicy.isRetryable(failure)) {
                val attemptedAt = clock.nowMs()
                // Mark each item for retry, but terminal-drop any that now exceed
                // the attempt/age thresholds (mirrors the single-send path).
                val survivors = group
                    .map { markForRetry(it, failure, attemptedAt) }
                    .filter { marked -> !dropIfTerminal(marked, attemptedAt) }
                return Outcome(survivors, 0, stop = true)
            }

            // Non-retryable batch failure → binary split retry.
            if (group.size > 1) {
                val splitIndex = group.size / 2
                val firstHalf = sendBatch(group.subList(0, splitIndex))
                if (firstHalf.stop) {
                    return Outcome(
                        firstHalf.reQueued + group.subList(splitIndex, group.size),
                        firstHalf.deliveredCount,
                        stop = true,
                    )
                }
                val secondHalf = sendBatch(group.subList(splitIndex, group.size))
                return Outcome(
                    firstHalf.reQueued + secondHalf.reQueued,
                    firstHalf.deliveredCount + secondHalf.deliveredCount,
                    stop = secondHalf.stop,
                )
            }

            // A single failing item falls back to per-request handling.
            val single = group.first()
            if (failure == null) {
                onDropped?.invoke(single, "unexpected_error")
                Outcome(emptyList(), 0, stop = false)
            } else {
                handleSingleFailure(single, failure)
            }
        }
    }

    /** Notify + report true if [marked] has hit a terminal-drop threshold. */
    private fun dropIfTerminal(marked: AttriaxQueuedRequest, nowMs: Long): Boolean {
        val terminal = AttriaxRetryPolicy.terminalDropReason(
            marked.request, marked.attemptCount, marked.createdAtMs, nowMs,
        ) ?: return false
        onDropped?.invoke(marked, terminal)
        return true
    }

    private fun markForRetry(
        queued: AttriaxQueuedRequest,
        failure: AttriaxFailure,
        attemptedAtMs: Long,
    ): AttriaxQueuedRequest {
        val nextAttempt = queued.attemptCount + 1
        return queued.copy(
            attemptCount = nextAttempt,
            lastAttemptAtMs = attemptedAtMs,
            lastErrorClass = AttriaxRetryPolicy.errorClass(failure),
            lastHttpStatusCode = AttriaxRetryPolicy.httpStatusCode(failure),
            nextRetryAtMs = AttriaxRetryPolicy.nextRetryAtMs(failure, attemptedAtMs, nextAttempt),
        )
    }

    private fun isWaitingForRetry(queued: AttriaxQueuedRequest): Boolean {
        val retryAt = queued.nextRetryAtMs ?: return false
        return retryAt > clock.nowMs()
    }

    private fun classify(e: Exception): AttriaxFailure? = when (e) {
        is AttriaxHttpException -> AttriaxFailure.Http(e.statusCode, headerOf(e.headers, "retry-after"))
        is AttriaxTimeoutException -> AttriaxFailure.Timeout
        is AttriaxTransportException -> AttriaxFailure.Transport
        else -> null
    }

    private fun headerOf(headers: Map<String, String>, name: String): String? {
        val lower = name.lowercase()
        return headers.entries.firstOrNull { it.key.lowercase() == lower }?.value
    }
}
