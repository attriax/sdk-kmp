package com.attriax.sdk.internal

/** Leveled log severity (Flutter reference `AttriaxLogger`). */
internal enum class AttriaxLogLevel { DEBUG, INFO, WARNING, ERROR }

/**
 * Output boundary for [AttriaxLogger]. The default forwards the line AND its severity
 * to the [attriaxLogEmit] platform seam, which maps it onto the host's native log
 * stream (logcat / NSLog / stdout+stderr); tests inject a capturing sink so gating can
 * be asserted without touching the real seam.
 */
internal fun interface AttriaxLogSink {
    fun emit(level: AttriaxLogLevel, line: String)

    companion object {
        /** Routes every level to the platform's native log stream, severity intact. */
        val PLATFORM = AttriaxLogSink { level, line -> attriaxLogEmit(level, line) }
    }
}

/**
 * Leveled logger (Flutter reference `AttriaxLogger`,
 * `attriax_logger.dart`). Gating mirrors Flutter: [debug]/[info] are suppressed
 * unless [enableDebugLogs] is set; [warn]/[error] always emit. Every line carries
 * the `[Attriax][LEVEL]` tag (Flutter's `[Attriax][$level] $message` format).
 *
 * The engine constructs one logger from [com.attriax.sdk.AttriaxConfig.enableDebugLogs]
 * and passes it wherever logging happens. Output is dependency-free — it flows
 * through the [sink], which defaults to the platform seams.
 */
internal class AttriaxLogger(
    private val enableDebugLogs: Boolean,
    private val sink: AttriaxLogSink = AttriaxLogSink.PLATFORM,
) {
    /** Verbose diagnostic — suppressed unless debug logging is enabled. */
    fun debug(message: String) {
        if (enableDebugLogs) sink.emit(AttriaxLogLevel.DEBUG, format(AttriaxLogLevel.DEBUG, message))
    }

    /** Informational — suppressed unless debug logging is enabled. */
    fun info(message: String) {
        if (enableDebugLogs) sink.emit(AttriaxLogLevel.INFO, format(AttriaxLogLevel.INFO, message))
    }

    /** Warning — always emitted (via the error seam, as the Flutter reference does). */
    fun warn(message: String) {
        sink.emit(AttriaxLogLevel.WARNING, format(AttriaxLogLevel.WARNING, message))
    }

    /** Error — always emitted. */
    fun error(message: String) {
        sink.emit(AttriaxLogLevel.ERROR, format(AttriaxLogLevel.ERROR, message))
    }

    private fun format(level: AttriaxLogLevel, message: String): String =
        "[Attriax][${level.name}] $message"

    companion object {
        /**
         * A logger that emits NOTHING at any level — including warn/error.
         *
         * Only for tests and for internal components whose production call site always
         * injects the engine's real logger. It must never be used as a production
         * fallback: an accidentally-silent default is precisely what made the whole SDK
         * undiagnosable, so silence here is opt-in and explicit.
         */
        val SILENT = AttriaxLogger(enableDebugLogs = false, sink = { _, _ -> })
    }
}
