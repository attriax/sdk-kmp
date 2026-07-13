package com.attriax.sdk.internal.queue

import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.request.AttriaxApiRequest
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** persistence + FIFO overflow eviction beyond maxQueueSize. */
class AttriaxQueueManagerTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    private fun queued(id: String) = AttriaxQueuedRequest(
        id = id,
        request = AttriaxApiRequest(
            AttriaxApiRequest.KIND_TRACK_EVENT, AttriaxEndpoints.EVENTS,
            linkedMapOf("projectToken" to "t", "eventName" to "e", "deviceId" to "d"),
        ),
        createdAtMs = 0L,
    )

    @Test
    fun persistsAndReloadsAcrossManagers() {
        val store = MapStore()
        AttriaxQueueManager(store, maxQueueSize = 10).enqueue(queued("q1"))

        val reloaded = AttriaxQueueManager(store, maxQueueSize = 10).readAll()
        assertEquals(1, reloaded.size)
        assertEquals("q1", reloaded.first().id)
    }

    @Test
    fun evictsOldestOnOverflowFifo() {
        val store = MapStore()
        val manager = AttriaxQueueManager(store, maxQueueSize = 3)
        listOf("q1", "q2", "q3", "q4", "q5").forEach { manager.enqueue(queued(it)) }

        val all = manager.readAll()
        assertEquals(3, all.size)
        // Oldest (q1, q2) evicted; tail preserved in order.
        assertEquals(listOf("q3", "q4", "q5"), all.map { it.id })
    }

    @Test
    fun writeAllPreservingNewKeepsRequestsEnqueuedDuringFlush() {
        // Simulates the flush/enqueue race: the flush snapshots [q1, q2], delivers
        // both (remaining empty), but q3 was enqueued concurrently. A plain
        // writeAll(empty) would clobber q3; writeAllPreservingNew must keep it.
        val store = MapStore()
        val manager = AttriaxQueueManager(store, maxQueueSize = 10)
        manager.enqueue(queued("q1"))
        manager.enqueue(queued("q2"))
        val snapshotIds = setOf("q1", "q2")
        // q3 arrives while the flush holds its snapshot.
        manager.enqueue(queued("q3"))

        manager.writeAllPreservingNew(remaining = emptyList(), snapshotIds = snapshotIds)

        assertEquals(listOf("q3"), manager.readAll().map { it.id })
    }

    @Test
    fun writeAllPreservingNewAppendsNewAfterRemainder() {
        val store = MapStore()
        val manager = AttriaxQueueManager(store, maxQueueSize = 10)
        manager.enqueue(queued("q1")) // stays queued (retryable)
        manager.enqueue(queued("q2"))
        manager.enqueue(queued("q3")) // enqueued during flush

        // Flush delivered q2, kept q1; q3 raced in.
        manager.writeAllPreservingNew(remaining = listOf(queued("q1")), snapshotIds = setOf("q1", "q2"))

        assertEquals(listOf("q1", "q3"), manager.readAll().map { it.id })
    }

    @Test
    fun writeAllEmptyClearsStorage() {
        val store = MapStore()
        val manager = AttriaxQueueManager(store, maxQueueSize = 10)
        manager.enqueue(queued("q1"))
        manager.writeAll(emptyList())
        assertTrue(manager.readAll().isEmpty())
        assertTrue(store.data[AttriaxQueueManager.KEY_QUEUE] == null)
    }
}
