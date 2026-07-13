package com.attriax.sdk.internal.json

import kotlin.math.floor

/**
 * A tiny, dependency-free JSON encoder/decoder for the SDK's simple wire shapes.
 *
 * Rationale (constraint): the wire shapes are flat maps/lists of
 * strings/numbers/bools/null. We deliberately avoid a heavy JSON dependency and
 * keep queue serialization, batching, and legacy normalization as PURE Kotlin so
 * they are fully unit-testable on every target off-device.
 *
 * Values in a decoded/encoded tree are one of:
 *   Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 */
object Json {

    // -------- Encoding --------

    fun encode(value: Any?): String {
        val sb = StringBuilder()
        encodeInto(sb, value)
        return sb.toString()
    }

    private fun encodeInto(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> encodeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is Double -> {
                if (value.isFinite()) {
                    // Emit integral doubles without a trailing ".0" to keep bytes tight.
                    if (value == floor(value) && !value.isInfinite()) {
                        sb.append(value.toLong().toString())
                    } else {
                        sb.append(value.toString())
                    }
                } else {
                    sb.append("null")
                }
            }
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    encodeString(sb, k.toString())
                    sb.append(':')
                    encodeInto(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    encodeInto(sb, v)
                }
                sb.append(']')
            }
            else -> encodeString(sb, value.toString())
        }
    }

    private fun encodeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u")
                    sb.append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    /** UTF-8 byte length of the encoded [value] — used for the batch size limit. */
    fun encodedByteSize(value: Any?): Int = encode(value).encodeToByteArray().size

    // -------- Decoding --------

    class JsonParseException(message: String) : Exception(message)

    fun decode(text: String): Any? = Parser(text).parseTopLevel()

    /** Decode and require a JSON object at the root. */
    fun decodeObject(text: String): Map<String, Any?> {
        val v = decode(text)
        if (v is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return v as Map<String, Any?>
        }
        throw JsonParseException("expected JSON object at root")
    }

    /** Decode and require a JSON array at the root. */
    fun decodeArray(text: String): List<Any?> {
        val v = decode(text)
        if (v is List<*>) return v
        throw JsonParseException("expected JSON array at root")
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseTopLevel(): Any? {
            skipWs()
            val v = parseValue()
            skipWs()
            if (pos != src.length) throw JsonParseException("trailing content at $pos")
            return v
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= src.length) throw JsonParseException("unexpected end of input")
            return when (val c = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> if (c == '-' || c in '0'..'9') parseNumber()
                else throw JsonParseException("unexpected char '$c' at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    '}' -> break
                    else -> throw JsonParseException("expected ',' or '}' at ${pos - 1}, got '$c'")
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> break
                    else -> throw JsonParseException("expected ',' or ']' at ${pos - 1}, got '$c'")
                }
            }
            return list
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= src.length) throw JsonParseException("unterminated string")
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw JsonParseException("bad escape")
                        when (val e = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                if (pos + 4 > src.length) throw JsonParseException("bad unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw JsonParseException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && src[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < src.length && src[pos] == '.') {
                isDouble = true
                pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos] in '0'..'9') pos++
            }
            val token = src.substring(start, pos)
            return if (isDouble) token.toDouble() else (token.toLongOrNull() ?: token.toDouble())
        }

        private fun parseBoolean(): Boolean {
            return when {
                src.startsWith("true", pos) -> { pos += 4; true }
                src.startsWith("false", pos) -> { pos += 5; false }
                else -> throw JsonParseException("invalid literal at $pos")
            }
        }

        private fun parseNull(): Any? {
            if (src.startsWith("null", pos)) { pos += 4; return null }
            throw JsonParseException("invalid literal at $pos")
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            if (pos >= src.length) throw JsonParseException("unexpected end of input")
            return src[pos]
        }

        private fun next(): Char {
            if (pos >= src.length) throw JsonParseException("unexpected end of input")
            return src[pos++]
        }

        private fun expect(c: Char) {
            val actual = next()
            if (actual != c) throw JsonParseException("expected '$c' at ${pos - 1}, got '$actual'")
        }
    }
}
