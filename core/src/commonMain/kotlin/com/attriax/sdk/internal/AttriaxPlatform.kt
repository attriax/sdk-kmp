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
 * used to stamp the crash `exceptionType` wire field.
 */
internal expect fun attriaxExceptionName(e: Throwable): String

/**
 * Emit one already-formatted, already-gated diagnostic line to the host platform's
 * NATIVE log stream. This is the single output seam behind [AttriaxLogger] — severity
 * is passed through so each platform can map it to its own priority rather than the
 * former stdout/stderr-only split (which was invisible on Android logcat and the Apple
 * unified log):
 *
 *  - androidMain: `android.util.Log` under the `Attriax` tag (d/i/w/e by level),
 *  - appleMain: `NSLog` (reaches Console.app / the device log; Kotlin/Native `println`
 *    does NOT),
 *  - jvmMain / desktopNativeMain: stdout for DEBUG/INFO, stderr for WARNING/ERROR.
 *
 * Callers must not gate on [AttriaxConfig.enableDebugLogs] here — [AttriaxLogger] owns
 * gating, and WARNING/ERROR reach this seam unconditionally by design.
 */
internal expect fun attriaxLogEmit(level: AttriaxLogLevel, line: String)

/**
 * A handle to an installed process-wide uncaught-exception handler (
 * Flutter `AttriaxCrashReportingManager` handler install/restore). [uninstall]
 * restores the previously-installed handler; it is idempotent and only restores
 * when our handler is still the active one.
 */
internal interface AttriaxUncaughtHandlerRegistration {
    fun uninstall()

    /** A registration that installed nothing (native placeholder / disabled path). */
    object Noop : AttriaxUncaughtHandlerRegistration {
        override fun uninstall() = Unit
    }
}

/**
 * Install a process-wide uncaught-exception handler that invokes [onFatalCrash]
 * SYNCHRONOUSLY (the process is dying — no background executor) before DELEGATING
 * to the previously-installed handler so the app's normal crash flow still runs.
 * Returns a registration whose [uninstall] restores the previous handler.
 *
 *  - androidMain / jvmMain: `Thread.setDefaultUncaughtExceptionHandler` — captures
 *    the previous handler, calls `onFatalCrash(throwable)`, then delegates.
 *  - appleMain: `NSSetUncaughtExceptionHandler` — captures `NSException`s, invokes
 *    `onFatalCrash`, then delegates to the previous handler (see
 *    `AttriaxUncaughtHandler.apple.kt`).
 *  - desktopNativeMain (mingwX64/linuxX64): NO OS-level capture — returns
 *    [AttriaxUncaughtHandlerRegistration.Noop] as a deliberate, documented limitation
 *    (a signal/SEH handler that persists on a dying process is not async-signal-safe;
 *    see `AttriaxUncaughtHandler.desktop.kt`). The common persist/replay + public
 *    `recordError` fatal API still work there — only the automatic OS-level capture is
 *    absent, and the manager logs a one-time info when the flag is on.
 */
internal expect fun attriaxInstallUncaughtExceptionHandler(
    onFatalCrash: (Throwable) -> Unit,
): AttriaxUncaughtHandlerRegistration
