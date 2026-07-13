package com.attriax.sdk.desktop

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toLong
import platform.windows.SW_SHOWNORMAL
import platform.windows.ShellExecuteW

/**
 * Windows-native browser open (PARITY §6). Asks the shell to `open` the URL with its
 * default handler via `ShellExecuteW` (shell32, already linked by
 * `platform.windows`). The `platform.windows` binding exposes the `LPCWSTR`
 * parameters as `String?`, marshalling the (http/https-validated) URL to UTF-16
 * internally. `ShellExecute` returns a value > 32 on success and a small error code
 * (≤ 32) on failure, which maps directly to the boolean.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxOpenInBrowser(url: String): Boolean {
    val result = ShellExecuteW(
        null,
        "open",
        url,
        null,
        null,
        SW_SHOWNORMAL,
    )
    // > 32 == success; ≤ 32 is a ShellExecute error code.
    return result.toLong() > 32
}
