package com.attriax.sdk.internal

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * JVM actuals for the engine platform seams (chunk 3). Identical to the Android
 * actuals — a daemon single-thread [ExecutorService] backs the executor.
 */
internal actual fun attriaxBackgroundExecutor(name: String): AttriaxBackgroundExecutor =
    AttriaxExecutorServiceBackgroundExecutor(name)

private class AttriaxExecutorServiceBackgroundExecutor(name: String) : AttriaxBackgroundExecutor {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    override fun execute(command: () -> Unit) {
        executor.submit(command)
    }

    override fun shutdown() {
        executor.shutdown()
    }

    override val isShutdown: Boolean get() = executor.isShutdown
}

internal actual fun attriaxExceptionName(e: Throwable): String = e.javaClass.name

internal actual fun attriaxLogError(message: String) {
    System.err.println(message)
}

internal actual fun attriaxLogInfo(message: String) {
    println(message)
}
