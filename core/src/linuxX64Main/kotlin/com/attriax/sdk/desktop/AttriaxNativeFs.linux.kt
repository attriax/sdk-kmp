package com.attriax.sdk.desktop

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.mkdir

/**
 * Linux-native `mkdir`: glibc declares the two-argument form taking a `mode_t`.
 * Created with `0700` (owner-only) since the store may hold device-identity data.
 * A non-zero return (typically already-exists) is ignored — best-effort, matching
 * the JVM `File.mkdirs()` seam.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxEnsureDir(path: String) {
    // 0700 = rwx for owner only.
    mkdir(path, "0700".toUInt(8).convert())
}
