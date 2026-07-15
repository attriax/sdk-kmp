package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxLogLevel
import com.attriax.sdk.internal.AttriaxLogSink
import com.attriax.sdk.internal.AttriaxLogger
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the DEFAULT [AttriaxLogSink.PLATFORM] sink to the real JVM streams.
 *
 * This is the coverage whose absence caused the logging blackout: `AttriaxLoggerTest`
 * only ever drives an INJECTED recording sink, so it proves the gating arithmetic while
 * saying nothing about whether the platform seam behind it emits anywhere at all. A
 * logger that is correct and connected to nothing still passes that suite.
 *
 * Asserted here on the JVM (the one target whose sink is capturable in-process):
 *  - DEBUG/INFO land on stdout, WARNING/ERROR land on stderr — so a host that captures
 *    only stderr for diagnostics still receives every failure,
 *  - a logger built with the PRODUCTION default sink (no injection) reaches those
 *    streams, and warn/error do so with `enableDebugLogs = false`.
 */
class AttriaxPlatformLogSinkTest {

    /** Runs [block] with stdout/stderr redirected, returning `(stdout, stderr)`. */
    private fun captureStreams(block: () -> Unit): Pair<String, String> {
        val outBuffer = ByteArrayOutputStream()
        val errBuffer = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(outBuffer, true))
            System.setErr(PrintStream(errBuffer, true))
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return outBuffer.toString() to errBuffer.toString()
    }

    @Test
    fun platformSinkRoutesDebugAndInfoToStdoutAndWarningAndErrorToStderr() {
        val (out, err) = captureStreams {
            AttriaxLogSink.PLATFORM.emit(AttriaxLogLevel.DEBUG, "d-line")
            AttriaxLogSink.PLATFORM.emit(AttriaxLogLevel.INFO, "i-line")
            AttriaxLogSink.PLATFORM.emit(AttriaxLogLevel.WARNING, "w-line")
            AttriaxLogSink.PLATFORM.emit(AttriaxLogLevel.ERROR, "e-line")
        }

        assertTrue(out.contains("d-line"), "DEBUG must reach stdout, got: $out")
        assertTrue(out.contains("i-line"), "INFO must reach stdout, got: $out")
        assertTrue(err.contains("w-line"), "WARNING must reach stderr, got: $err")
        assertTrue(err.contains("e-line"), "ERROR must reach stderr, got: $err")
        assertTrue(!out.contains("w-line") && !out.contains("e-line"), "warn/error must not hit stdout")
        assertTrue(!err.contains("d-line") && !err.contains("i-line"), "debug/info must not hit stderr")
    }

    @Test
    fun defaultConstructedLoggerEmitsWarningsAndErrorsWithDebugLogsOff() {
        // NO sink injected — exactly how the engine builds its logger in production.
        val logger = AttriaxLogger(enableDebugLogs = false)

        val (out, err) = captureStreams {
            logger.debug("suppressed-debug")
            logger.info("suppressed-info")
            logger.warn("surfaced-warn")
            logger.error("surfaced-error")
        }

        assertEquals("", out.trim(), "debug/info must stay suppressed when debug logs are off")
        assertTrue(
            err.contains("[Attriax][WARNING] surfaced-warn"),
            "warn must surface through the real platform sink with debug logs OFF, got: $err",
        )
        assertTrue(
            err.contains("[Attriax][ERROR] surfaced-error"),
            "error must surface through the real platform sink with debug logs OFF, got: $err",
        )
    }

    @Test
    fun defaultConstructedLoggerEmitsDebugAndInfoWhenDebugLogsOn() {
        val logger = AttriaxLogger(enableDebugLogs = true)

        val (out, _) = captureStreams {
            logger.debug("visible-debug")
            logger.info("visible-info")
        }

        assertTrue(out.contains("[Attriax][DEBUG] visible-debug"), "got: $out")
        assertTrue(out.contains("[Attriax][INFO] visible-info"), "got: $out")
    }
}
