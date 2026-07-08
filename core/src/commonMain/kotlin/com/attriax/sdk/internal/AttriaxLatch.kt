package com.attriax.sdk.internal

/**
 * A one-shot countdown latch seam (replaces `java.util.concurrent.CountDownLatch`
 * + `TimeUnit`), backing the deep-link initial-link probe's bounded blocking wait.
 * JVM/Android delegate to `CountDownLatch`; native uses a simple deadline spin.
 */
internal expect class AttriaxLatch(count: Int) {
    fun countDown()

    /** Block until the count reaches zero or [timeoutMs] elapses. Returns true when it reached zero. */
    fun await(timeoutMs: Long): Boolean
}
