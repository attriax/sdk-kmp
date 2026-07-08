package com.attriax.sdk.internal

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext

/**
 * Native actuals for the engine platform seams (chunk 3), now backed by a REAL
 * off-thread coroutine dispatcher on Kotlin/Native desktop (mingwX64 / linuxX64).
 *
 * This replaces the former COMPILE-ONLY synchronous placeholder: fire-and-forget
 * background work (flush + consent sync) now runs on a dedicated single background
 * thread, mirroring the JVM/Android daemon single-thread `ExecutorService` — the
 * caller thread is never blocked, and a task failure never crashes the host. The
 * shared engine tests already tolerate async execution (they are green on the JVM,
 * whose executor is likewise a real background thread).
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal actual fun attriaxBackgroundExecutor(name: String): AttriaxBackgroundExecutor =
    AttriaxCoroutineBackgroundExecutor(name)

/**
 * Single-thread coroutine-backed background executor. Semantics parity with the
 * JVM `Executors.newSingleThreadExecutor` seam:
 *  - [execute] posts the command to the background thread (fire-and-forget) and
 *    returns immediately; a throwing command is swallowed (like `submit`, whose
 *    failure is captured in the discarded Future) so it never kills the thread,
 *  - [shutdown] is idempotent — it cancels the scope (stops accepting/running work)
 *    and closes the dedicated dispatcher thread,
 *  - [isShutdown] reflects whether [shutdown] has been called.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private class AttriaxCoroutineBackgroundExecutor(name: String) : AttriaxBackgroundExecutor {
    private val shutdownFlag = atomic(false)
    private val dispatcher = newSingleThreadContext(name)
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    override fun execute(command: () -> Unit) {
        if (shutdownFlag.value) return
        scope.launch {
            try {
                command()
            } catch (e: Throwable) {
                // A background failure must never crash the host or kill the thread
                // (parity with the JVM executor, whose task exception is swallowed by
                // the discarded Future).
            }
        }
    }

    override fun shutdown() {
        // Idempotent: only the first caller tears the thread down.
        if (shutdownFlag.compareAndSet(expect = false, update = true)) {
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

    override val isShutdown: Boolean get() = shutdownFlag.value
}

internal actual fun attriaxExceptionName(e: Throwable): String =
    e::class.qualifiedName ?: e::class.simpleName ?: "kotlin.Throwable"

internal actual fun attriaxLogError(message: String) {
    println(message)
}

internal actual fun attriaxLogInfo(message: String) {
    println(message)
}

/**
 * COMPILE-ONLY placeholder: native uncaught-exception capture is NOT installed yet.
 *
 * Real OS-level capture — iOS `NSSetUncaughtExceptionHandler` (at the Mac) and POSIX
 * signal handlers (`SIGSEGV`/`SIGABRT`/...) — lands with the platform chunk. Until
 * then this returns a no-op registration so every native target (mingwX64 +
 * linuxX64) compiles and the shared engine runs. The common persist/replay path and
 * the public `recordError(fatal = true)` wrapper API still work on native; only the
 * automatic OS-level capture is deferred.
 */
internal actual fun attriaxInstallUncaughtExceptionHandler(
    onFatalCrash: (Throwable) -> Unit,
): AttriaxUncaughtHandlerRegistration = AttriaxUncaughtHandlerRegistration.Noop
