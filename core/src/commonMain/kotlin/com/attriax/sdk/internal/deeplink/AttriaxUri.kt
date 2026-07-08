package com.attriax.sdk.internal.deeplink

/**
 * A tiny, framework-free URI value used by the deep-link core (PARITY §6).
 *
 * Rationale: the deep-link normalization/metadata logic (linkPath stripping,
 * query-parameter extraction, Attriax-domain detection) must be pure so it is
 * unit-testable WITHOUT `android.net.Uri`. The thin Android adapter
 * (`AttriaxDeepLinkIntents`) hands the SDK a raw URI string pulled from an
 * `android.content.Intent`; parsing happens here.
 *
 * This is intentionally minimal — it covers the shapes real deep links take
 * (`scheme://host/path?query`, `https://sub.attriax.com/abc?x=1`, custom
 * `myapp://open/thing`) without aiming to be a full RFC 3986 implementation.
 */
data class AttriaxUri(
    val raw: String,
    val scheme: String?,
    val host: String?,
    val path: String,
    /** Query parameters preserving multiplicity + order (mirrors Dart `queryParametersAll`). */
    val queryParametersAll: Map<String, List<String>>,
) {
    fun isScheme(candidate: String): Boolean =
        scheme != null && scheme.equals(candidate, ignoreCase = true)

    override fun toString(): String = raw

    companion object {
        /** Parse [rawInput], returning null for blank/unparseable input. */
        fun parse(rawInput: String?): AttriaxUri? {
            if (rawInput == null) return null
            val raw = rawInput.trim()
            if (raw.isEmpty()) return null

            var rest = raw
            var scheme: String? = null

            // scheme:
            val schemeMatch = SCHEME_REGEX.find(rest)
            if (schemeMatch != null) {
                scheme = schemeMatch.groupValues[1].lowercase()
                rest = rest.substring(schemeMatch.value.length)
            }

            // Strip fragment (#...) — not used by resolution.
            val fragmentIdx = rest.indexOf('#')
            if (fragmentIdx >= 0) rest = rest.substring(0, fragmentIdx)

            // Split query.
            val query: String
            val queryIdx = rest.indexOf('?')
            if (queryIdx >= 0) {
                query = rest.substring(queryIdx + 1)
                rest = rest.substring(0, queryIdx)
            } else {
                query = ""
            }

            var host: String? = null
            var path: String
            if (rest.startsWith("//")) {
                // authority form: //host[/path]
                val afterSlashes = rest.substring(2)
                val slashIdx = afterSlashes.indexOf('/')
                if (slashIdx >= 0) {
                    host = stripUserInfoAndPort(afterSlashes.substring(0, slashIdx))
                    path = afterSlashes.substring(slashIdx)
                } else {
                    host = stripUserInfoAndPort(afterSlashes)
                    path = ""
                }
            } else {
                // No authority (custom scheme like `myapp:open/thing`) or relative.
                path = rest
            }

            return AttriaxUri(
                raw = raw,
                scheme = scheme,
                host = host,
                path = path,
                queryParametersAll = parseQuery(query),
            )
        }

        private fun stripUserInfoAndPort(authority: String): String {
            var a = authority
            val at = a.lastIndexOf('@')
            if (at >= 0) a = a.substring(at + 1)
            val colon = a.indexOf(':')
            if (colon >= 0) a = a.substring(0, colon)
            return a
        }

        private fun parseQuery(query: String): Map<String, List<String>> {
            if (query.isEmpty()) return emptyMap()
            val result = LinkedHashMap<String, MutableList<String>>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key: String
                val value: String
                if (eq >= 0) {
                    key = decode(pair.substring(0, eq))
                    value = decode(pair.substring(eq + 1))
                } else {
                    key = decode(pair)
                    value = ""
                }
                result.getOrPut(key) { ArrayList() }.add(value)
            }
            return result
        }

        private fun decode(value: String): String {
            if (value.indexOf('%') < 0 && value.indexOf('+') < 0) return value
            val sb = StringBuilder(value.length)
            var i = 0
            val bytes = ArrayList<Byte>()
            fun flushBytes() {
                if (bytes.isEmpty()) return
                sb.append(bytes.toByteArray().decodeToString())
                bytes.clear()
            }
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '%' && i + 2 < value.length -> {
                        val hex = value.substring(i + 1, i + 3)
                        val b = hex.toIntOrNull(16)
                        if (b != null) {
                            bytes.add(b.toByte())
                            i += 3
                        } else {
                            flushBytes(); sb.append(c); i++
                        }
                    }
                    c == '+' -> { flushBytes(); sb.append(' '); i++ }
                    else -> { flushBytes(); sb.append(c); i++ }
                }
            }
            flushBytes()
            return sb.toString()
        }

        private val SCHEME_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
    }
}
