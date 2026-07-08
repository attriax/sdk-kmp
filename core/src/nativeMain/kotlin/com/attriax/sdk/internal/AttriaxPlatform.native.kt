package com.attriax.sdk.internal

import kotlinx.atomicfu.atomic

/**
 * Native actuals for the engine platform seams (chunk 3).
 *
 * The background executor is a COMPILE-ONLY placeholder: `execute` runs the task
 * SYNCHRONOUSLY inline. Real native off-thread background execution lands with the
 * desktop chunk; until then this keeps every target compiling and lets the shared
 * engine tests run deterministically on native.
 */
internal actual fun attriaxBackgroundExecutor(name: String): AttriaxBackgroundExecutor =
    AttriaxInlineBackgroundExecutor()

private class AttriaxInlineBackgroundExecutor : AttriaxBackgroundExecutor {
    private val shutdownFlag = atomic(false)

    override fun execute(command: () -> Unit) {
        // INTERIM: real native background threading lands with the desktop chunk.
        // Until then, run inline so the shared engine stays deterministic on native.
        if (!shutdownFlag.value) command()
    }

    override fun shutdown() {
        shutdownFlag.value = true
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
