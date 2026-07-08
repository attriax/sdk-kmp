package com.attriax.sdk.internal

import kotlinx.atomicfu.atomic
import kotlinx.datetime.Clock

/**
 * Native latch: a monotone-decrementing counter with a deadline spin. The engine
 * only awaits the initial-link probe (which resolves quickly), so a coarse poll is
 * adequate here; a real OS condition variable can replace it later.
 */
internal actual class AttriaxLatch actual constructor(count: Int) {
    private val remaining = atomic(count)

    actual fun countDown() {
        while (true) {
            val current = remaining.value
            if (current <= 0) return
            if (remaining.compareAndSet(current, current - 1)) return
        }
    }

    actual fun await(timeoutMs: Long): Boolean {
        if (remaining.value <= 0) return true
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        while (remaining.value > 0) {
            if (Clock.System.now().toEpochMilliseconds() >= deadline) return false
            attriaxSleep(5)
        }
        return true
    }
}
