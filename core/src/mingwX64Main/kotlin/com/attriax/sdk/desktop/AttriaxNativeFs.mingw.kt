package com.attriax.sdk.desktop

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.mkdir

/**
 * Windows-native `mkdir`: MinGW declares the mode-less single-argument form. A
 * non-zero return (typically because the directory already exists) is ignored —
 * best-effort, matching the JVM `File.mkdirs()` seam.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxEnsureDir(path: String) {
    mkdir(path)
}
