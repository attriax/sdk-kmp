package com.attriax.sdk.jvm

import com.attriax.sdk.internal.AttriaxScheduler
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * JVM-desktop [AttriaxScheduler] backed by a single daemon
 * [ScheduledExecutorService], mirroring Android's
 * [com.attriax.sdk.android.AttriaxExecutorScheduler]: heartbeat/deferred-flush
 * timers run OFF the caller thread and never leak — a cancelled handle cancels its
 * future and [shutdown] tears the pool down. A tick that throws is swallowed so a
 * failure never crashes the host or kills the timer.
 */
class AttriaxJvmScheduler : AttriaxScheduler {

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "attriax-session").apply { isDaemon = true }
        }

    override fun schedulePeriodic(
        intervalMs: Long,
        action: () -> Unit,
    ): AttriaxScheduler.ScheduledHandle {
        val future = executor.scheduleAtFixedRate(
            {
                try {
                    action()
                } catch (e: Exception) {
                    // A heartbeat failure must never crash the host or kill the timer.
                }
            },
            intervalMs,
            intervalMs,
            TimeUnit.MILLISECONDS,
        )
        return AttriaxScheduler.ScheduledHandle { future.cancel(false) }
    }

    override fun scheduleOnce(
        delayMs: Long,
        action: () -> Unit,
    ): AttriaxScheduler.ScheduledHandle {
        val future = executor.schedule(
            {
                try {
                    action()
                } catch (e: Exception) {
                    // A deferred-flush failure must never crash the host or the pool.
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        return AttriaxScheduler.ScheduledHandle { future.cancel(false) }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
