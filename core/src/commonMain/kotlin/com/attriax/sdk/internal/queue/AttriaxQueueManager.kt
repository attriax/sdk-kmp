package com.attriax.sdk.internal.queue

import com.attriax.sdk.internal.KeyValueStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Persists the outbound request queue to a [KeyValueStore] (PARITY §7, row Q1).
 *
 * All ordering/serialization/corruption logic lives in [AttriaxQueueCodec]
 * (pure); this class adds the persistence + FIFO overflow eviction beyond
 * `maxQueueSize`, guarded by a coarse lock so concurrent enqueue/flush do not
 * interleave a read-modify-write.
 */
class AttriaxQueueManager(
    private val store: KeyValueStore,
    private val maxQueueSize: Int,
) {
    private val lock = SynchronizedObject()

    fun readAll(): List<AttriaxQueuedRequest> = synchronized(lock) { readAllUnlocked() }

    private fun readAllUnlocked(): List<AttriaxQueuedRequest> {
        val result = AttriaxQueueCodec.decode(store.getString(KEY_QUEUE))
        if (result.clearedWholePayload) {
            store.remove(KEY_QUEUE)
            return emptyList()
        }
        if (result.droppedEntryCount > 0) {
            // Rewrite the pruned queue so the invalid entries do not resurface.
            writeAllUnlocked(result.queue)
        }
        return result.queue
    }

    fun enqueue(request: AttriaxQueuedRequest) {
        synchronized(lock) {
            val queue = ArrayList(readAllUnlocked())
            queue.add(request)
            if (queue.size > maxQueueSize) {
                val overflow = queue.size - maxQueueSize
                // FIFO: evict the oldest entries at the head.
                repeat(overflow) { queue.removeAt(0) }
            }
            writeAllUnlocked(queue)
        }
    }

    fun writeAll(queue: List<AttriaxQueuedRequest>) {
        synchronized(lock) { writeAllUnlocked(queue) }
    }

    /**
     * Persist a flushed queue [remaining] WITHOUT clobbering requests that were
     * enqueued concurrently during the flush (PARITY §7). The dispatcher snapshots
     * the queue at the start of a flush; any request appended while the flush was
     * in flight (its id is in neither [remaining] nor [snapshotIds]) must be
     * preserved and appended after the flushed remainder — otherwise a plain
     * `writeAll(remaining)` would silently drop it. This closes a flush/enqueue
     * race that is especially important for retry-exempt kinds (deep-link resolve),
     * which must never be lost.
     */
    fun writeAllPreservingNew(
        remaining: List<AttriaxQueuedRequest>,
        snapshotIds: Set<String>,
    ) = synchronized(lock) {
        val remainingIds = remaining.mapTo(HashSet()) { it.id }
        val newlyAdded = readAllUnlocked().filter {
            it.id !in snapshotIds && it.id !in remainingIds
        }
        writeAllUnlocked(remaining + newlyAdded)
    }

    /**
     * Atomically rewrite queued entries (PARITY §5, row C5 consent reconciliation).
     * [transform] returns a replacement entry, or null to leave the entry as-is.
     * Returns the number of entries actually changed. The read-modify-write is
     * guarded by the same lock as enqueue/flush so no concurrent mutation can
     * interleave against a half-rewritten queue.
     */
    fun rewriteWhere(
        transform: (AttriaxQueuedRequest) -> AttriaxQueuedRequest?,
    ): Int = synchronized(lock) {
        val current = readAllUnlocked()
        var changed = 0
        val rewritten = current.map { entry ->
            val replacement = transform(entry)
            if (replacement != null && replacement != entry) {
                changed++
                replacement
            } else {
                entry
            }
        }
        if (changed > 0) writeAllUnlocked(rewritten)
        changed
    }

    /**
     * Atomically discard queued entries matching [predicate] (PARITY §5, row C5
     * pass 3). Returns the number of entries removed.
     */
    fun discardWhere(predicate: (AttriaxQueuedRequest) -> Boolean): Int = synchronized(lock) {
        val current = readAllUnlocked()
        val kept = current.filterNot(predicate)
        val removed = current.size - kept.size
        if (removed > 0) writeAllUnlocked(kept)
        removed
    }

    private fun writeAllUnlocked(queue: List<AttriaxQueuedRequest>) {
        if (queue.isEmpty()) {
            store.remove(KEY_QUEUE)
            return
        }
        store.putString(KEY_QUEUE, AttriaxQueueCodec.encode(queue))
    }

    companion object {
        const val KEY_QUEUE = "attriax.request_queue"
    }
}
