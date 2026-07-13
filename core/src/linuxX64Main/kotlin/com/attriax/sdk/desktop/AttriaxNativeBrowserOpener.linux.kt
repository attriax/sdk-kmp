package com.attriax.sdk.desktop

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.system

/**
 * Linux-native browser open (PARITY §6). Delegates to the freedesktop `xdg-open`
 * helper via `system`. The (http/https-validated) URL is wrapped in single quotes
 * and any embedded single quote is escaped (`'\''`) so it cannot break out of the
 * quoting or inject a shell command. Output is discarded.
 *
 * Fails soft: `system` returns -1 when no shell can be spawned and the command's exit
 * status otherwise — 127 when `xdg-open` is not installed — so only a genuine
 * dispatch (exit 0) maps to `true`.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxOpenInBrowser(url: String): Boolean {
    val escaped = url.replace("'", "'\\''")
    val status = system("xdg-open '$escaped' >/dev/null 2>&1")
    return status == 0
}
