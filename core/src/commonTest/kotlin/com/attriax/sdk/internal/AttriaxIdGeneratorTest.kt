package com.attriax.sdk.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** id shape + deterministic formatting. */
class AttriaxIdGeneratorTest {

    @Test
    fun formatsSixteenBytesWithDashesAt3579() {
        val bytes = ByteArray(16) { it.toByte() } // 00 01 02 ... 0f
        val id = AttriaxIdGenerator.formatId(bytes)

        // Dashes after bytes 3,5,7,9 → segments of 4,2,2,2,6 bytes = hex 8-4-4-4-12.
        assertEquals("00010203-0405-0607-0809-0a0b0c0d0e0f", id)
    }

    @Test
    fun rendersHighBytesAsLowercaseHex() {
        val bytes = ByteArray(16) { 0xFF.toByte() }
        val id = AttriaxIdGenerator.formatId(bytes)
        assertEquals("ffffffff-ffff-ffff-ffff-ffffffffffff", id)
    }

    @Test
    fun idHasCanonicalUuidLikeShape() {
        val id = AttriaxIdGenerator.generate()
        assertTrue(id.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")))
    }

    @Test
    fun generatesDistinctIds() {
        assertNotEquals(AttriaxIdGenerator.generate(), AttriaxIdGenerator.generate())
    }

    @Test
    fun rejectsWrongByteLength() {
        assertFailsWith<IllegalArgumentException> {
            AttriaxIdGenerator.formatId(ByteArray(15))
        }
    }
}
