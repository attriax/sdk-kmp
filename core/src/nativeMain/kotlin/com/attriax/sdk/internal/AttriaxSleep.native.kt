package com.attriax.sdk.internal

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep

@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxSleep(ms: Long) {
    if (ms <= 0) return
    usleep((ms * 1000).toUInt())
}
