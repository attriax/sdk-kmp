package com.attriax.sdk.apple

import com.attriax.sdk.internal.AttriaxScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

/**
 * Apple [AttriaxScheduler] for the session heartbeat / deferred flush.
 * Structurally identical to the desktop `AttriaxNativeScheduler`: timers run OFF the caller
 * thread on a dedicated single-thread coroutine dispatcher and never leak — a
 * cancelled handle cancels its coroutine and [shutdown] tears the dispatcher down.
 *
 * `delay` (not a blocking sleep) is used so a cancel takes effect promptly, and a
 * tick that throws is swallowed so a heartbeat/flush failure never crashes the host
 * or kills the timer.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class AttriaxAppleScheduler : AttriaxScheduler {

    private val dispatcher = newSingleThreadContext("attriax-session")
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    override fun schedulePeriodic(
        intervalMs: Long,
        action: () -> Unit,
    ): AttriaxScheduler.ScheduledHandle {
        // First tick after one interval, then every interval — parity with the JVM
        // `scheduleAtFixedRate(interval, interval)` and the desktop scheduler.
        val job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    action()
                } catch (e: Throwable) {
                    // A heartbeat failure must never crash the host or kill the timer.
                }
            }
        }
        return AttriaxScheduler.ScheduledHandle { job.cancel() }
    }

    override fun scheduleOnce(
        delayMs: Long,
        action: () -> Unit,
    ): AttriaxScheduler.ScheduledHandle {
        val job = scope.launch {
            delay(delayMs)
            try {
                action()
            } catch (e: Throwable) {
                // A deferred-flush failure must never crash the host or the pool.
            }
        }
        return AttriaxScheduler.ScheduledHandle { job.cancel() }
    }

    fun shutdown() {
        try {
            scope.cancel()
        } catch (e: Throwable) {
            // best-effort teardown
        }
        try {
            dispatcher.close()
        } catch (e: Throwable) {
            // best-effort teardown
        }
    }
}
