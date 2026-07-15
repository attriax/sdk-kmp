package com.attriax.sdk.internal

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.stderr

/**
 * Desktop-native sink for the [attriaxLogEmit] seam (mingwX64 / linuxX64).
 *
 * Mirrors the JVM actual so a desktop host sees the same stream split regardless of
 * which core it embeds: DEBUG/INFO → stdout, WARNING/ERROR → stderr. The former shared
 * native actual sent BOTH to stdout, so a host redirecting only stderr for diagnostics
 * lost every warning.
 *
 * `stderr` is flushed explicitly — it is unbuffered on POSIX but NOT when a Windows host
 * redirects it to a pipe or file, which would otherwise strand the final warnings of a
 * crashing process in the buffer.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxLogEmit(level: AttriaxLogLevel, line: String) {
    when (level) {
        AttriaxLogLevel.DEBUG, AttriaxLogLevel.INFO -> println(line)
        AttriaxLogLevel.WARNING, AttriaxLogLevel.ERROR -> {
            fprintf(stderr, "%s\n", line)
            fflush(stderr)
        }
    }
}
