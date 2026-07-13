package com.attriax.sdk.internal.attestation

import com.attriax.sdk.internal.AttriaxHttpException
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * the challenge fetcher over the [HttpClient] port. Grounds the
 * challenge parse against the api `AttestationChallengeResponseDto`
 * (`{ nonce, expiresInSeconds }`). Fake transport → no device/network.
 */
class AttriaxAttestationChallengeFetcherTest {

    private class StubTransport(
        val response: () -> HttpResponse,
    ) : HttpClient {
        val posts = ArrayList<Pair<String, String>>()
        override fun post(path: String, body: String): HttpResponse {
            posts.add(path to body)
            return response.invoke()
        }
    }

    @Test
    fun parsesNonceAndExpiry() {
        val transport = StubTransport {
            HttpResponse(200, """{"nonce":"server_nonce","expiresInSeconds":120}""")
        }
        val challenge = AttriaxAttestationChallengeFetcher(transport).fetch()

        assertEquals("server_nonce", challenge!!.nonce)
        assertEquals(120, challenge.expiresInSeconds)
        // Hits the exact challenge endpoint path.
        assertEquals(AttriaxEndpoints.ATTESTATION_CHALLENGE, transport.posts.single().first)
    }

    @Test
    fun parsesNonceWithoutExpiry() {
        val transport = StubTransport { HttpResponse(200, """{"nonce":"n"}""") }
        val challenge = AttriaxAttestationChallengeFetcher(transport).fetch()
        assertEquals("n", challenge!!.nonce)
        assertNull(challenge.expiresInSeconds)
    }

    @Test
    fun missingNonceYieldsNull() {
        val transport = StubTransport { HttpResponse(200, """{"expiresInSeconds":30}""") }
        assertNull(AttriaxAttestationChallengeFetcher(transport).fetch())
    }

    @Test
    fun blankNonceYieldsNull() {
        val transport = StubTransport { HttpResponse(200, """{"nonce":"   "}""") }
        assertNull(AttriaxAttestationChallengeFetcher(transport).fetch())
    }

    @Test
    fun emptyBodyYieldsNull() {
        val transport = StubTransport { HttpResponse(200, null) }
        assertNull(AttriaxAttestationChallengeFetcher(transport).fetch())
    }

    @Test
    fun malformedBodyYieldsNull() {
        val transport = StubTransport { HttpResponse(200, "not-json") }
        assertNull(AttriaxAttestationChallengeFetcher(transport).fetch())
    }

    @Test
    fun nonObjectBodyYieldsNull() {
        val transport = StubTransport { HttpResponse(200, """["nonce"]""") }
        assertNull(AttriaxAttestationChallengeFetcher(transport).fetch())
    }

    /**
     * A transport-thrown HTTP error (e.g. challenge outage → 5xx) PROPAGATES from
     * the fetcher; the manager's own catch turns it into "no envelope".
     * This documents the boundary: the fetcher never breaks init only because the
     * manager wraps it.
     */
    @Test
    fun transportErrorPropagatesToManager() {
        val transport = StubTransport { throw AttriaxHttpException(503, "down") }
        assertFailsWith<AttriaxHttpException> {
            AttriaxAttestationChallengeFetcher(transport).fetch()
        }
    }
}
