package com.attriax.sdk.internal

/**
 * Platform seams for the engine entrypoint (chunk 3).
 *
 * These replace the last `java.*` couplings in the ported [com.attriax.sdk.Attriax]
 * engine + its public facades with `expect`/`actual` declarations, so the shared
 * behavior stays identical while the platform primitives are swapped per target.
 */

/**
 * A background command executor with an explicit lifecycle. Extends the existing
 * [AttriaxExecutor] so a single instance can back both the engine's own flush/consent
 * executors AND be passed directly as the consent manager's `syncExecutor`.
 *
 * On JVM/Android it wraps a daemon single-thread `Executor`; on native it is a
 * COMPILE-ONLY synchronous placeholder until the desktop chunk lands real threading.
 */
internal interface AttriaxBackgroundExecutor : AttriaxExecutor {
    /** Stop accepting new work (idempotent). */
    fun shutdown()

    /** Whether [shutdown] has been called. */
    val isShutdown: Boolean
}

/**
 * Create a single-thread background executor named [name]. Injected into the engine
 * with defaults so the pure engine + tests can substitute a synchronous fake.
 */
internal expect fun attriaxBackgroundExecutor(name: String): AttriaxBackgroundExecutor

/**
 * The fully-qualified exception type name (replaces `Throwable.javaClass.name`),
 * used to stamp the crash `exceptionType` wire field (PARITY §4).
 */
internal expect fun attriaxExceptionName(e: Throwable): String

/** Emit a diagnostic error line (replaces `System.err.println`). */
internal expect fun attriaxLogError(message: String)

/**
 * Emit a non-error diagnostic line (debug/info levels). Routed to stdout so it is
 * distinct from the [attriaxLogError] stderr channel and stays dependency-free.
 */
internal expect fun attriaxLogInfo(message: String)
