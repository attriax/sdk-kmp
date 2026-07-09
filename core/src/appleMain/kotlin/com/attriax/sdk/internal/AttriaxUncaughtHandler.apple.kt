package com.attriax.sdk.internal

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSException
import platform.Foundation.NSGetUncaughtExceptionHandler
import platform.Foundation.NSSetUncaughtExceptionHandler

/**
 * Apple actual for the OS uncaught-exception handler seam (PARITY §4).
 *
 * Installs a process-wide Objective-C uncaught-exception handler via
 * `NSSetUncaughtExceptionHandler` (mirrors the Flutter reference
 * `AttriaxCrashReportingManager` handler install/restore, and the standalone iOS
 * SDK's crash capture). On a fatal `NSException` it invokes [onFatalCrash]
 * SYNCHRONOUSLY (the process is dying — no background executor) with the exception
 * wrapped as a [Throwable], then DELEGATES to the previously-installed handler so the
 * app's normal crash flow (and any other crash reporter) still runs.
 *
 * A C uncaught-exception handler must be a `staticCFunction`, which cannot capture
 * state, so the live callback + the previous handler are held in top-level state
 * guarded by [installLock]. Only one handler is installed at a time; [uninstall]
 * restores the previous handler and clears the callback, but only when OUR handler is
 * still the active one (idempotent, and it never clobbers a handler a later installer
 * put in place).
 *
 * Scope note: like the reference, this captures Objective-C `NSException`s (UIKit /
 * Foundation / StoreKit failures). Kotlin/Native's own unhandled-exception path and
 * POSIX signals are a separate mechanism; the common persist/replay + the public
 * `recordError(fatal = true)` API cover manual reporting regardless.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxInstallUncaughtExceptionHandler(
    onFatalCrash: (Throwable) -> Unit,
): AttriaxUncaughtHandlerRegistration = synchronized(installLock) {
    activeCallback = onFatalCrash
    previousHandler = NSGetUncaughtExceptionHandler()
    NSSetUncaughtExceptionHandler(attriaxUncaughtExceptionTrampoline)
    AttriaxAppleUncaughtHandlerRegistration
}

/** Lock guarding the top-level handler state (install/uninstall/dispatch). */
private val installLock = SynchronizedObject()

/** The live fatal-crash callback, or null once uninstalled. */
private var activeCallback: ((Throwable) -> Unit)? = null

/** The handler that was installed before ours, restored on [uninstall]. */
@OptIn(ExperimentalForeignApi::class)
private var previousHandler: CPointer<CFunction<(NSException?) -> Unit>>? = null

/**
 * The `staticCFunction` installed with `NSSetUncaughtExceptionHandler`. Reads the
 * live callback + previous handler from top-level state (a static C function cannot
 * capture), invokes the callback synchronously, then delegates to the previous
 * handler. Failures inside the callback are swallowed — the process is already
 * terminating and delegation must still run.
 */
@OptIn(ExperimentalForeignApi::class)
private val attriaxUncaughtExceptionTrampoline: CPointer<CFunction<(NSException?) -> Unit>> =
    staticCFunction { exception: NSException? ->
        val callback = synchronized(installLock) { activeCallback }
        if (callback != null && exception != null) {
            try {
                callback(exception.toThrowable())
            } catch (_: Throwable) {
                // The process is dying; never let reporting mask the original crash.
            }
        }
        val previous = synchronized(installLock) { previousHandler }
        previous?.invoke(exception)
    }

/** The single registration handle; [uninstall] restores the previous handler. */
private object AttriaxAppleUncaughtHandlerRegistration : AttriaxUncaughtHandlerRegistration {
    @OptIn(ExperimentalForeignApi::class)
    override fun uninstall() = synchronized(installLock) {
        // Only restore when OUR trampoline is still the active handler, so a later
        // installer's handler is never clobbered (matches the reference's guarded
        // restore).
        if (NSGetUncaughtExceptionHandler() == attriaxUncaughtExceptionTrampoline) {
            NSSetUncaughtExceptionHandler(previousHandler)
        }
        activeCallback = null
        previousHandler = null
    }
}

/** Wrap an `NSException` as a [Throwable] carrying its name + reason for the crash wire shape. */
private fun NSException.toThrowable(): Throwable {
    val exceptionName = name ?: "NSException"
    val detail = reason
    val message = if (detail.isNullOrBlank()) exceptionName else "$exceptionName: $detail"
    return AttriaxNativeException(message)
}

/** A plain [Throwable] representing a captured Objective-C `NSException`. */
private class AttriaxNativeException(message: String) : RuntimeException(message)
