package com.attriax.sdk.desktop

import kotlinx.atomicfu.atomic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the native coroutine `delay`-based [AttriaxNativeScheduler]: periodic
 * ticks fire and cancel, and one-shots fire exactly once (and can be cancelled
 * before they fire).
 */
class AttriaxNativeSchedulerTest {

    @Test
    fun schedulePeriodicFiresRepeatedlyThenCancels() {
        val scheduler = AttriaxNativeScheduler()
        try {
            val count = atomic(0)
            val handle = scheduler.schedulePeriodic(30L) { count.incrementAndGet() }
            assertTrue(waitUntil(5_000L) { count.value >= 3 }, "periodic timer fired at least 3 times")

            handle.cancel()
            val afterCancel = count.value
            sleepMs(300L)
            // No more than one further in-flight tick after cancel.
            assertTrue(count.value <= afterCancel + 1, "cancel stops future ticks")
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun scheduleOnceFiresExactlyOnce() {
        val scheduler = AttriaxNativeScheduler()
        try {
            val count = atomic(0)
            scheduler.scheduleOnce(30L) { count.incrementAndGet() }
            assertTrue(waitUntil(5_000L) { count.value == 1 }, "one-shot fired")
            sleepMs(300L)
            assertEquals(1, count.value, "one-shot fires exactly once")
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun cancelBeforeFirePreventsTheTick() {
        val scheduler = AttriaxNativeScheduler()
        try {
            val count = atomic(0)
            val handle = scheduler.scheduleOnce(500L) { count.incrementAndGet() }
            handle.cancel()
            sleepMs(800L)
            assertEquals(0, count.value, "cancel before the delay elapses prevents the tick")
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun shutdownStopsPendingTicksAndIsIdempotent() {
        val scheduler = AttriaxNativeScheduler()
        val count = atomic(0)
        scheduler.scheduleOnce(200L) { count.incrementAndGet() }
        scheduler.schedulePeriodic(100L) { count.incrementAndGet() }

        scheduler.shutdown()
        scheduler.shutdown() // double-dispose path: must be a safe no-op

        sleepMs(500L)
        assertEquals(0, count.value, "no scheduled tick may fire after shutdown")
    }

    @Test
    fun scheduleAfterShutdownIsANonThrowingNoop() {
        val scheduler = AttriaxNativeScheduler()
        scheduler.shutdown()

        val count = atomic(0)
        // Dispose-then-call contract: launching into the cancelled scope never
        // dispatches and never throws; the returned handles stay cancellable.
        val once = scheduler.scheduleOnce(10L) { count.incrementAndGet() }
        val periodic = scheduler.schedulePeriodic(10L) { count.incrementAndGet() }
        once.cancel()
        periodic.cancel()
        sleepMs(200L)
        assertEquals(0, count.value, "nothing may fire after shutdown")
    }
}
