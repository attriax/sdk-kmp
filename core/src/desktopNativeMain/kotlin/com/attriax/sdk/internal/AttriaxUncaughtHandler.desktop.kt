package com.attriax.sdk.internal

/**
 * COMPILE-ONLY placeholder: desktop-native (mingwX64/linuxX64) uncaught-exception
 * capture is NOT installed yet.
 *
 * Real OS-level capture on desktop (POSIX signal handlers — `SIGSEGV`/`SIGABRT`/...)
 * is a later follow-up; until then this returns a no-op registration so the desktop
 * targets compile and the shared engine runs. The common persist/replay path and the
 * public `recordError(fatal = true)` wrapper API still work — only the automatic
 * OS-level auto-capture is deferred. (Apple provides a real handler via
 * `AttriaxUncaughtHandler.apple.kt`.)
 */
internal actual fun attriaxInstallUncaughtExceptionHandler(
    onFatalCrash: (Throwable) -> Unit,
): AttriaxUncaughtHandlerRegistration = AttriaxUncaughtHandlerRegistration.Noop
