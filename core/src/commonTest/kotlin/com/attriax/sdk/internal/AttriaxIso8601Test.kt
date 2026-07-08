package com.attriax.sdk.internal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The consent-timestamp formatter is WIRE-VISIBLE: it must reproduce the exact
 * `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (UTC) string the Android SDK produced with
 * `SimpleDateFormat(Locale.US)` — EXACTLY 3 millisecond digits, trailing zeros
 * KEPT, trailing literal `Z`.
 */
class AttriaxIso8601Test {

    @Test
    fun epochZeroFormatsWithThreeZeroMillis() {
        assertEquals("1970-01-01T00:00:00.000Z", AttriaxIso8601.formatUtcMillis(0L))
    }

    @Test
    fun knownInstantFormatsExactly() {
        // 1994-11-06T08:49:37Z == 784_111_777 seconds since epoch.
        assertEquals("1994-11-06T08:49:37.000Z", AttriaxIso8601.formatUtcMillis(784_111_777_000L))
    }

    @Test
    fun millisEndingInZeroAreKeptNotTrimmed() {
        // The critical case: .700 must NOT collapse to .7 (kotlinx-datetime toString would).
        assertEquals("1994-11-06T08:49:37.700Z", AttriaxIso8601.formatUtcMillis(784_111_777_700L))
    }

    @Test
    fun subTenMillisAreZeroPaddedToThreeDigits() {
        assertEquals("1994-11-06T08:49:37.005Z", AttriaxIso8601.formatUtcMillis(784_111_777_005L))
    }

    @Test
    fun fullThreeDigitMillisArePreserved() {
        assertEquals("1994-11-06T08:49:37.123Z", AttriaxIso8601.formatUtcMillis(784_111_777_123L))
    }

    @Test
    fun parsesIsoStringToEpochMillis() {
        assertEquals(784_111_777_700L, AttriaxIso8601.parseUtcMillisOrNull("1994-11-06T08:49:37.700Z"))
        assertEquals(0L, AttriaxIso8601.parseUtcMillisOrNull("1970-01-01T00:00:00.000Z"))
    }

    @Test
    fun parseReturnsNullForUnparseableValue() {
        assertEquals(null, AttriaxIso8601.parseUtcMillisOrNull("not-a-date"))
        assertEquals(null, AttriaxIso8601.parseUtcMillisOrNull(""))
    }

    @Test
    fun formatAndParseRoundTrip() {
        val ms = 784_111_777_123L
        assertEquals(ms, AttriaxIso8601.parseUtcMillisOrNull(AttriaxIso8601.formatUtcMillis(ms)))
    }
}
