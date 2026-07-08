package com.attriax.sdk.internal

import kotlinx.datetime.Clock

/** Time source port so timestamp-dependent logic stays deterministic in tests. */
fun interface AttriaxClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = AttriaxClock { Clock.System.now().toEpochMilliseconds() }
    }
}
