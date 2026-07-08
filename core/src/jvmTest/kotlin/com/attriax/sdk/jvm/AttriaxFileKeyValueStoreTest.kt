package com.attriax.sdk.jvm

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttriaxFileKeyValueStoreTest {

    private lateinit var dir: File

    @BeforeTest
    fun setup() {
        dir = File.createTempFile("attriax-store", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun putGetRemoveRoundtrip() {
        val store = AttriaxFileKeyValueStore(dir)
        assertNull(store.getString("k"))

        store.putString("k", "v1")
        assertEquals("v1", store.getString("k"))

        store.putString("k", "v2")
        assertEquals("v2", store.getString("k"))

        store.remove("k")
        assertNull(store.getString("k"))

        // putString(null) also removes.
        store.putString("k2", "x")
        store.putString("k2", null)
        assertNull(store.getString("k2"))
    }

    @Test
    fun persistsAcrossAFreshInstanceOnTheSameDir() {
        val first = AttriaxFileKeyValueStore(dir)
        first.putString("attriax.device_id", "abc-123")
        first.putString("queue", """[{"a":1}]""")

        // A brand-new instance on the same dir (simulates a process restart).
        val second = AttriaxFileKeyValueStore(dir)
        assertEquals("abc-123", second.getString("attriax.device_id"))
        assertEquals("""[{"a":1}]""", second.getString("queue"))
    }

    @Test
    fun concurrentAccessDoesNotCorrupt() {
        val store = AttriaxFileKeyValueStore(dir)
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(perThread) { i ->
                    store.putString("key-$t", "val-$t-$i")
                    store.getString("key-$t")
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        // Every thread's last write is intact and readable from a fresh instance,
        // proving no torn/corrupt file.
        val reopened = AttriaxFileKeyValueStore(dir)
        repeat(threads) { t ->
            assertEquals("val-$t-${perThread - 1}", reopened.getString("key-$t"))
        }
    }
}
