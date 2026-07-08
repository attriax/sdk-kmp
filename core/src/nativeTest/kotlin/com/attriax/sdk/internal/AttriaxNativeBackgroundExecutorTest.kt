package com.attriax.sdk.internal

import com.attriax.sdk.desktop.sleepMs
import com.attriax.sdk.desktop.waitUntil
import kotlinx.atomicfu.atomic
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the native (coroutine-dispatcher) background executor: work runs
 * off-thread and completes, and [AttriaxBackgroundExecutor.shutdown] stops it.
 */
class AttriaxNativeBackgroundExecutorTest {

    @Test
    fun executeRunsTheTaskOnABackgroundThreadAndCompletes() {
        val executor = attriaxBackgroundExecutor("attriax-test-exec")
        try {
            val ran = atomic(false)
            executor.execute { ran.value = true }
            // Fire-and-forget: execute() returned immediately; the task lands on the
            // background thread shortly after.
            assertTrue(waitUntil(5_000L) { ran.value }, "background task ran and completed")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun manyTasksAllRun() {
        val executor = attriaxBackgroundExecutor("attriax-test-exec-many")
        try {
            val count = atomic(0)
            repeat(50) { executor.execute { count.incrementAndGet() } }
            assertTrue(waitUntil(5_000L) { count.value == 50 }, "all 50 tasks ran")
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun shutdownStopsAcceptingWork() {
        val executor = attriaxBackgroundExecutor("attriax-test-exec-shutdown")
        executor.shutdown()
        assertTrue(executor.isShutdown, "isShutdown reflects the teardown")

        val ran = atomic(false)
        executor.execute { ran.value = true }
        // Give any (incorrectly) accepted task a chance to run, then assert none did.
        sleepMs(200L)
        assertFalse(ran.value, "no work runs after shutdown")
    }

    @Test
    fun aThrowingTaskDoesNotKillTheThread() {
        val executor = attriaxBackgroundExecutor("attriax-test-exec-throw")
        try {
            executor.execute { throw RuntimeException("boom") }
            val ran = atomic(false)
            executor.execute { ran.value = true }
            // The thread survived the first task's failure and still runs the next.
            assertTrue(waitUntil(5_000L) { ran.value }, "executor survives a throwing task")
        } finally {
            executor.shutdown()
        }
    }
}
