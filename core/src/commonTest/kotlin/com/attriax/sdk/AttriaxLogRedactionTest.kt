package com.attriax.sdk

import com.attriax.sdk.internal.attriaxRedactUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Pins the log redaction applied to deep-link URLs before they reach a device log.
 *
 * These lines are emitted at warn, which is UNGATED by `enableDebugLogs` and therefore
 * reaches logcat / the Apple unified log on release builds — so anything this helper
 * fails to strip is shipped to customer devices.
 */
class AttriaxLogRedactionTest {

    @Test
    fun keepsSchemeAndHostAndDropsPathAndQuery() {
        assertEquals(
            "https://links.example.com/[redacted]",
            attriaxRedactUrl("https://links.example.com/invite/abc123?token=secret&utm_source=x"),
        )
    }

    @Test
    fun dropsUserInfoCredentialsAndPort() {
        assertEquals(
            "https://example.com/[redacted]",
            attriaxRedactUrl("https://user:pa55w0rd@example.com:8443/path?a=b"),
        )
    }

    @Test
    fun dropsFragment() {
        assertEquals(
            "https://example.com/[redacted]",
            attriaxRedactUrl("https://example.com/p#access_token=secret"),
        )
    }

    @Test
    fun redactsCustomSchemeLinkWithNoHost() {
        assertEquals("myapp:[redacted]", attriaxRedactUrl("myapp:open/thing?user=alice@example.com"))
    }

    @Test
    fun keepsHostForCustomSchemeAuthorityForm() {
        assertEquals("myapp://open/[redacted]", attriaxRedactUrl("myapp://open/thing?user=alice"))
    }

    @Test
    fun handlesNullBlankAndUnparseableInput() {
        assertEquals("(no URL)", attriaxRedactUrl(null))
        assertEquals("(no URL)", attriaxRedactUrl("   "))
        assertEquals("(URL with no scheme)", attriaxRedactUrl("/just/a/path?x=1"))
    }

    @Test
    fun neverLeaksSensitiveSubstringsForRealisticDeepLinks() {
        val urls = listOf(
            "https://go.example.com/r/AB12?token=eyJhbGciOi&email=alice%40example.com",
            "https://go.example.com/u/alice%40example.com#t=secret",
            "myapp://invite/CODE-99?ref=alice",
        )
        val leaks = listOf("token", "eyJhbGciOi", "alice", "example.com/r", "CODE-99", "secret", "AB12")

        for (url in urls) {
            val redacted = attriaxRedactUrl(url)
            for (leak in leaks) {
                assertFalse(
                    redacted.contains(leak, ignoreCase = true),
                    "redacted form of <$url> must not contain \"$leak\", got: $redacted",
                )
            }
        }
    }
}
