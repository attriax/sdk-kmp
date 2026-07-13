package com.attriax.sdk.internal.dispatch

import com.attriax.sdk.internal.queue.AttriaxQueuedRequest

/**
 * App-open-first ordering (
 * Flutter `dispatcher.dart:515-530` `_prioritizeAppOpenRequests`).
 *
 * Enforced positionally at flush, not by a lock: any app-open request is hoisted
 * to the front of the flush order, preserving the relative order of both the
 * hoisted opens and the remaining requests (a stable partition).
 */
object AttriaxAppOpenHoist {
    fun prioritize(queue: List<AttriaxQueuedRequest>): List<AttriaxQueuedRequest> {
        val opens = ArrayList<AttriaxQueuedRequest>()
        val others = ArrayList<AttriaxQueuedRequest>()
        for (queued in queue) {
            if (queued.request.isAppOpen) opens.add(queued) else others.add(queued)
        }
        return opens + others
    }
}
