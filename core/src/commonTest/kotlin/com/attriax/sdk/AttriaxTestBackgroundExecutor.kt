package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxBackgroundExecutor

/**
 * Synchronous [AttriaxBackgroundExecutor] for the engine tests: `execute` runs the
 * task inline so the flush / consent-reconciliation paths complete deterministically
 * on every target (jvm AND native), with no wall-clock polling. Mirrors the async
 * production executor's observable end state without its threading.
 */
internal class AttriaxTestBackgroundExecutor : AttriaxBackgroundExecutor {
    private var shutdownFlag = false

    override fun execute(command: () -> Unit) {
        if (!shutdownFlag) command()
    }

    override fun shutdown() {
        shutdownFlag = true
    }

    override val isShutdown: Boolean get() = shutdownFlag
}
