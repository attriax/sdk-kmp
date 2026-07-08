package com.attriax.sdk.jvm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttriaxJvmSchedulerTest {

    @Test
    fun schedulePeriodicFiresRepeatedlyThenCancels() {
        val scheduler = AttriaxJvmScheduler()
        try {
            val latch = CountDownLatch(3)
            val count = AtomicInteger(0)
            val handle = scheduler.schedulePeriodic(30L) {
                count.incrementAndGet()
                latch.countDown()
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "periodic timer fired at least 3 times")

            handle.cancel()
            val afterCancel = count.get()
            Thread.sleep(200L)
            // No further ticks after cancel (allow the in-flight tick already counted).
            assertTrue(count.get() <= afterCancel + 1, "cancel stops future ticks")
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun scheduleOnceFiresExactlyOnce() {
        val scheduler = AttriaxJvmScheduler()
        try {
            val latch = CountDownLatch(1)
            val count = AtomicInteger(0)
            scheduler.scheduleOnce(30L) {
                count.incrementAndGet()
                latch.countDown()
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            Thread.sleep(200L)
            assertEquals(1, count.get(), "one-shot fires exactly once")
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun cancelBeforeFirePreventsTheTick() {
        val scheduler = AttriaxJvmScheduler()
        try {
            val count = AtomicInteger(0)
            val handle = scheduler.scheduleOnce(500L) { count.incrementAndGet() }
            handle.cancel()
            Thread.sleep(800L)
            assertEquals(0, count.get(), "cancel before the delay elapses prevents the tick")
        } finally {
            scheduler.shutdown()
        }
    }
}
