package com.attriax.sdk.core

/**
 * Toolchain probe — the minimal shape of the shared core: pure common logic plus
 * one `expect`/`actual` platform seam. If this compiles for every declared target
 * and its commonTest passes, the KMP migration thesis holds. Deleted once the real
 * core lands in commonMain.
 */
expect fun platformName(): String

object Probe {
    fun greeting(): String = "attriax-kmp on ${platformName()}"
}
