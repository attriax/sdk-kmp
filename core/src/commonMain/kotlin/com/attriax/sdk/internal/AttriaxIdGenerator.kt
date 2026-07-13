package com.attriax.sdk.internal

/**
 * Generates UUID-v4-*like* identifiers used for device ids AND queued-request ids
 * (S1; Flutter `internal/attriax_id_generator.dart:5-16`).
 *
 * Format: 16 random bytes rendered lowercase hex, with dashes inserted after
 * bytes 3, 5, 7 and 9. The backend treats the value as opaque, so only the
 * SHAPE matters — this is not a spec-compliant UUID.
 *
 * The byte generation is factored out ([formatId]) so tests can assert the exact
 * formatting deterministically without an RNG, while production draws from the
 * platform [attriaxSecureRandomBytes] seam.
 */
object AttriaxIdGenerator {

    /** Generate a new random id from the platform random-bytes seam. */
    fun generate(): String = formatId(attriaxSecureRandomBytes(16))

    /**
     * Render [bytes] as the Attriax id string. Requires exactly 16 bytes.
     *
     * Pure — no RNG, no platform types — so format determinism is unit-testable.
     */
    fun formatId(bytes: ByteArray): String {
        require(bytes.size == 16) { "Attriax id requires exactly 16 bytes, got ${bytes.size}" }
        val buffer = StringBuilder(36)
        for (i in bytes.indices) {
            val unsigned = bytes[i].toInt() and 0xFF
            buffer.append(HEX[unsigned ushr 4])
            buffer.append(HEX[unsigned and 0x0F])
            if (i == 3 || i == 5 || i == 7 || i == 9) {
                buffer.append('-')
            }
        }
        return buffer.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
