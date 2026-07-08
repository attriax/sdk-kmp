package com.attriax.sdk.internal.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The hand-rolled JSON codec underpins queue serialization + batch sizing. */
class JsonTest {

    @Test
    fun roundtripsNestedStructures() {
        val value = linkedMapOf<String, Any?>(
            "s" to "hello",
            "n" to 42L,
            "b" to true,
            "nil" to null,
            "list" to listOf(1L, 2L, "three"),
            "obj" to mapOf("k" to "v"),
        )
        val encoded = Json.encode(value)
        val decoded = Json.decodeObject(encoded)
        assertEquals("hello", decoded["s"])
        assertEquals(42L, decoded["n"])
        assertEquals(true, decoded["b"])
        assertTrue(decoded.containsKey("nil"))
        assertEquals(listOf(1L, 2L, "three"), decoded["list"])
    }

    @Test
    fun escapesSpecialCharacters() {
        val encoded = Json.encode(mapOf("k" to "a\"b\\c\nd\te"))
        val decoded = Json.decodeObject(encoded)
        assertEquals("a\"b\\c\nd\te", decoded["k"])
    }

    @Test
    fun encodedByteSizeUsesUtf8() {
        // A 3-byte UTF-8 char inside a JSON string.
        val size = Json.encodedByteSize("€")
        // "€" → quote + 3 bytes + quote = 5.
        assertEquals(5, size)
    }

    @Test
    fun rejectsTrailingContent() {
        assertFailsWith<Json.JsonParseException> {
            Json.decode("{} garbage")
        }
    }

    @Test
    fun decodeArrayRejectsObjectRoot() {
        assertFailsWith<Json.JsonParseException> {
            Json.decodeArray("{}")
        }
    }
}
