package com.attriax.sdk.desktop

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.datetime.Clock
import platform.posix.getenv
import platform.posix.usleep

/**
 * Bounded busy-wait for [predicate] to become true, polling every 5ms up to
 * [timeoutMs]. Returns true if the predicate held before the deadline. Used to
 * await fire-and-forget background work on the native targets without a JVM latch.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
    val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
    while (!predicate()) {
        if (Clock.System.now().toEpochMilliseconds() >= deadline) return false
        usleep(5_000u) // 5ms
    }
    return true
}

/** Sleep [ms] milliseconds (native test helper). */
@OptIn(ExperimentalForeignApi::class)
internal fun sleepMs(ms: Long) {
    if (ms > 0) usleep((ms * 1000).toUInt())
}

/** A unique, writable temp directory path for a store test (not created here). */
@OptIn(ExperimentalForeignApi::class)
internal fun uniqueTempDir(tag: String): String {
    val base = getenv("TEMP")?.toKString()?.takeIf { it.isNotBlank() }
        ?: getenv("TMP")?.toKString()?.takeIf { it.isNotBlank() }
        ?: getenv("TMPDIR")?.toKString()?.takeIf { it.isNotBlank() }
        ?: "."
    val stamp = Clock.System.now().toEpochMilliseconds()
    return joinPath(base, "attriax-native-$tag-$stamp")
}
