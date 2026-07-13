package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxLogLevel
import com.attriax.sdk.internal.AttriaxLogSink
import com.attriax.sdk.internal.AttriaxLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Leveled-logger gating (Flutter reference `AttriaxLogger`). Driven with
 * an injected recording sink so gating is asserted without touching the platform
 * stdout/stderr seams: debug/info are gated behind `enableDebugLogs`; warn/error
 * always emit. Deterministic on jvm AND native.
 */
class AttriaxLoggerTest {

    private class RecordingSink : AttriaxLogSink {
        val lines = mutableListOf<Pair<AttriaxLogLevel, String>>()
        override fun emit(level: AttriaxLogLevel, line: String) {
            lines.add(level to line)
        }
    }

    @Test
    fun debugAndInfoAreSuppressedWhenDebugLogsDisabled() {
        val sink = RecordingSink()
        val logger = AttriaxLogger(enableDebugLogs = false, sink = sink)

        logger.debug("d")
        logger.info("i")

        assertTrue(sink.lines.isEmpty(), "debug/info must be suppressed when debug logs are off")
    }

    @Test
    fun warnAndErrorAlwaysEmitEvenWhenDebugLogsDisabled() {
        val sink = RecordingSink()
        val logger = AttriaxLogger(enableDebugLogs = false, sink = sink)

        logger.warn("w")
        logger.error("e")

        assertEquals(2, sink.lines.size)
        assertEquals(AttriaxLogLevel.WARNING, sink.lines[0].first)
        assertEquals("[Attriax][WARNING] w", sink.lines[0].second)
        assertEquals(AttriaxLogLevel.ERROR, sink.lines[1].first)
        assertEquals("[Attriax][ERROR] e", sink.lines[1].second)
    }

    @Test
    fun debugAndInfoEmitWhenDebugLogsEnabled() {
        val sink = RecordingSink()
        val logger = AttriaxLogger(enableDebugLogs = true, sink = sink)

        logger.debug("d")
        logger.info("i")

        assertEquals(2, sink.lines.size)
        assertEquals(AttriaxLogLevel.DEBUG, sink.lines[0].first)
        assertEquals("[Attriax][DEBUG] d", sink.lines[0].second)
        assertEquals(AttriaxLogLevel.INFO, sink.lines[1].first)
        assertEquals("[Attriax][INFO] i", sink.lines[1].second)
    }
}
