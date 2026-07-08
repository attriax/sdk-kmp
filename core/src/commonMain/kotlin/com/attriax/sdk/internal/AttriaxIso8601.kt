package com.attriax.sdk.internal

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Formats an epoch-millis timestamp as the EXACT wire ISO-8601 string the backend
 * expects for consent (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`, UTC).
 *
 * This reproduces the JVM `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)`
 * used in the Android SDK: UTC, literal `T`, EXACTLY 3-digit milliseconds (trailing
 * zeros KEPT), trailing literal `Z`. kotlinx-datetime's `Instant.toString()` trims
 * fractional-second zeros, so the string is built by hand from the UTC datetime
 * components with fixed zero-padding.
 */
internal object AttriaxIso8601 {
    fun formatUtcMillis(epochMs: Long): String {
        val dt = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC)
        val millis = ((epochMs % 1000) + 1000) % 1000 // non-negative millis-of-second
        val sb = StringBuilder(24)
        pad(sb, dt.year, 4)
        sb.append('-')
        pad(sb, dt.monthNumber, 2)
        sb.append('-')
        pad(sb, dt.dayOfMonth, 2)
        sb.append('T')
        pad(sb, dt.hour, 2)
        sb.append(':')
        pad(sb, dt.minute, 2)
        sb.append(':')
        pad(sb, dt.second, 2)
        sb.append('.')
        pad(sb, millis.toInt(), 3)
        sb.append('Z')
        return sb.toString()
    }

    private fun pad(sb: StringBuilder, value: Int, width: Int) {
        val s = value.toString()
        repeat((width - s.length).coerceAtLeast(0)) { sb.append('0') }
        sb.append(s)
    }

    /**
     * Parse an ISO-8601 timestamp string to epoch-millis, or `null` when it is not a
     * parseable instant. Mirrors the Flutter reference's `DateTime.tryParse` used by
     * `attriaxDateTimeValue` (attriax_json_utils.dart) to read the server's
     * click/consume timestamps — a soft parse that yields null rather than throwing.
     * Delegates to kotlinx-datetime `Instant.parse`, which accepts the RFC-3339 /
     * ISO-8601 forms the backend emits (e.g. `2024-01-02T03:04:05.678Z`).
     */
    fun parseUtcMillisOrNull(value: String): Long? = try {
        Instant.parse(value.trim()).toEpochMilliseconds()
    } catch (e: Exception) {
        null
    }
}
