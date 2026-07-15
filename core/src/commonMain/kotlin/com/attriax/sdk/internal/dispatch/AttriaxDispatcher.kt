package com.attriax.sdk.internal.dispatch

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxHttpException
import com.attriax.sdk.internal.AttriaxLogger
import com.attriax.sdk.internal.AttriaxTimeoutException
import com.attriax.sdk.internal.attriaxExceptionName
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
 *
 * `internal` visibility is load-bearing on Apple: this engine-plumbing class shares its
 * simple name with the PUBLIC command table [com.attriax.sdk.AttriaxDispatcher] object.
 * Kotlin/Native's Obj-C export cannot emit two `AttriaxCoreAttriaxDispatcher` classes,
 * so leaving this one `public` silently drops the command object (+ its
 * `AttriaxDispatchResult`) from the XCFramework — breaking every Swift binding that
 * forwards to `AttriaxDispatcher.execute`. This is only referenced by [Attriax] in-module.
 */
internal class AttriaxDispatcher(
    private val queue: AttriaxQueueManager,
    private val transport: HttpClient,
    private val clock: AttriaxClock,
    /**
     * Diagnostics. The engine injects its real logger; the [AttriaxLogger.SILENT]
     * default keeps the pure dispatcher tests off the platform log stream. Delivery
     * outcomes log at debug (gated by `enableDebugLogs`) while every failure, retry and
     * drop logs at warn/error, so a customer sees WHY nothing arrives even with debug
     * logging off. Only the request `kind`, endpoint path, error class and HTTP status
     * are logged — never the payload body, project token, or device id.
     */
    private val logger: AttriaxLogger = AttriaxLogger.SILENT,
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
            if (ordered.isEmpty()) {
                logger.debug("Flush skipped: the request queue is empty.")
                return 0
            }
            logger.debug("Flush starting: ${ordered.size} request(s) queued.")

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
            logger.debug("Flush finished: $delivered delivered, ${remaining.size} still queued.")
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
            logger.debug("Delivered ${queued.request.kind} to ${queued.request.path}.")
            Outcome(emptyList(), 1, stop = false)
        } catch (e: Exception) {
            val failure = classify(e) ?: run {
                // Non-transport exception — treat as a hard drop to avoid a poison loop.
                logger.error(
                    "Dropping ${queued.request.kind}: unexpected ${attriaxExceptionName(e)} " +
                        "while sending to ${queued.request.path}. ${e.message}",
                )
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
            logger.warn(
                "${queued.request.kind} to ${queued.request.path} failed " +
                    "(${describe(failure)}); it stays queued for retry " +
                    "(attempt ${marked.attemptCount}). Halting this flush pass.",
            )
            return Outcome(listOf(marked), 0, stop = true)
        }

        // Non-retryable (other 4xx) → drop.
        logger.error(
            "Dropping ${queued.request.kind} to ${queued.request.path}: " +
                "non-retryable ${describe(failure)}. Check that the project token is valid " +
                "and that the payload is well-formed — this request will NOT be retried.",
        )
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
            logger.debug("Delivered a batch of ${group.size} request(s).")
            Outcome(emptyList(), group.size, stop = false)
        } catch (e: Exception) {
            val failure = classify(e)
            if (failure != null && AttriaxRetryPolicy.isRetryable(failure)) {
                val attemptedAt = clock.nowMs()
                logger.warn(
                    "Batch of ${group.size} request(s) failed (${describe(failure)}); " +
                        "the items stay queued for retry. Halting this flush pass.",
                )
                // Mark each item for retry, but terminal-drop any that now exceed
                // the attempt/age thresholds (mirrors the single-send path).
                val survivors = group
                    .map { markForRetry(it, failure, attemptedAt) }
                    .filter { marked -> !dropIfTerminal(marked, attemptedAt) }
                return Outcome(survivors, 0, stop = true)
            }

            // Non-retryable batch failure → binary split retry.
            if (group.size > 1) {
                logger.warn(
                    "Batch of ${group.size} request(s) failed with a non-retryable " +
                        "${failure?.let { describe(it) } ?: attriaxExceptionName(e)}; " +
                        "splitting it to isolate the offending request(s).",
                )
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
                logger.error(
                    "Dropping ${single.request.kind}: unexpected ${attriaxExceptionName(e)} " +
                        "while sending a single-item batch. ${e.message}",
                )
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
        logger.error(
            "Dropping ${marked.request.kind} to ${marked.request.path} PERMANENTLY after " +
                "${marked.attemptCount} attempt(s): $terminal. The data in this request is lost.",
        )
        onDropped?.invoke(marked, terminal)
        return true
    }

    /**
     * A privacy-safe, human-readable rendering of a failure for a log line: the error
     * class plus the HTTP status when there is one. Never includes a response body —
     * it can echo the submitted payload back.
     */
    private fun describe(failure: AttriaxFailure): String {
        val errorClass = AttriaxRetryPolicy.errorClass(failure)
        val status = AttriaxRetryPolicy.httpStatusCode(failure)
        return if (status != null) "$errorClass, HTTP $status" else errorClass
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
