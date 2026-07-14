package com.attriax.sdk.internal

/**
 * Desktop-native (mingwX64/linuxX64): NO OS-level uncaught-exception auto-capture.
 *
 * This is a deliberate, documented limitation, not an unfinished stub. OS-level
 * capture on desktop-native would mean a POSIX signal handler (`SIGSEGV`/`SIGABRT`/…)
 * or Windows `SetUnhandledExceptionFilter`, but a fatal handler on this path must
 * SYNCHRONOUSLY persist the crash (file I/O + JSON encode) while the process is
 * terminating. Those operations are not async-signal-safe, so a signal handler doing
 * them is fragile and can deadlock/re-crash — we intentionally do NOT install one
 * rather than ship an unsafe handler.
 *
 * Consequences (kept truthful — see [com.attriax.sdk.AttriaxConfig
 * .automaticCrashReportingEnabled] and [AttriaxCrashReportingManager.install]): this
 * returns [AttriaxUncaughtHandlerRegistration.Noop]; the manager logs a one-time info
 * that auto-capture is unavailable when the flag is on. The common persist/replay path
 * and the public `recordError(fatal = true)` wrapper API still work on desktop — only
 * the AUTOMATIC OS-level capture is absent here. (Apple provides a real handler via
 * `AttriaxUncaughtHandler.apple.kt`; Android/JVM via `Thread` default handler.)
 */
internal actual fun attriaxInstallUncaughtExceptionHandler(
    onFatalCrash: (Throwable) -> Unit,
): AttriaxUncaughtHandlerRegistration = AttriaxUncaughtHandlerRegistration.Noop
