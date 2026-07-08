package com.attriax.sdk.internal

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Android actuals for the engine platform seams (chunk 3). Identical to the JVM
 * actuals — a daemon single-thread [ExecutorService] backs the executor; the
 * exception name / error log delegate to the JVM primitives the original SDK used.
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

internal actual fun attriaxInstallUncaughtExceptionHandler(
    onFatalCrash: (Throwable) -> Unit,
): AttriaxUncaughtHandlerRegistration {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    val installed = Thread.UncaughtExceptionHandler { thread, throwable ->
        try {
            // SYNCHRONOUS persist — the process is dying, so nothing async can run.
            onFatalCrash(throwable)
        } catch (_: Throwable) {
            // Never mask the original crash with a reporting failure.
        }
        // DELEGATE so the app's normal crash flow (default handler / OS dialog) runs.
        previous?.uncaughtException(thread, throwable)
    }
    Thread.setDefaultUncaughtExceptionHandler(installed)
    return object : AttriaxUncaughtHandlerRegistration {
        override fun uninstall() {
            // Only restore when our handler is still the active one (do not clobber a
            // handler someone else installed after us).
            if (Thread.getDefaultUncaughtExceptionHandler() === installed) {
                Thread.setDefaultUncaughtExceptionHandler(previous)
            }
        }
    }
}
