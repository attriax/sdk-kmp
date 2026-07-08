package com.attriax.sdk.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal actual class AttriaxLatch actual constructor(count: Int) {
    private val latch = CountDownLatch(count)
    actual fun countDown() = latch.countDown()
    actual fun await(timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
