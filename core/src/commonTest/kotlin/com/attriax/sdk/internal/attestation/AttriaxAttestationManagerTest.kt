package com.attriax.sdk.internal.attestation

import com.attriax.sdk.AttriaxAttestationProvider
import com.attriax.sdk.AttriaxAttestationToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PARITY §9 rows AT1/AT2 — pure attestation flow (Flutter reference
 * `attriax_attestation_manager_test.dart`). No device / Play Services: the
 * challenge fetch and the provider are fakes, so the whole envelope-assembly + the
 * never-break-init invariant is unit-tested.
 */
class AttriaxAttestationManagerTest {

    /** A provider whose `attest` is scripted (token, null, or throw). */
    private class FakeProvider(
        val attest: (String) -> AttriaxAttestationToken?,
    ) : AttriaxAttestationProvider {
        var calls = 0
        var lastNonce: String? = null
        override fun attest(nonce: String): AttriaxAttestationToken? {
            calls++
            lastNonce = nonce
            return attest.invoke(nonce)
        }
    }

    // -------- AT1: enabled + provider returns a token → envelope carried --------

    @Test
    fun enabledWithTokenAssemblesEnvelopeWithSlugAndNonce() {
        val provider = FakeProvider { AttriaxAttestationToken(token = "integrity_token") }
        var challengeCalls = 0
        val manager = AttriaxAttestationManager(
            enabled = true,
            provider = provider,
            fetchChallenge = {
                challengeCalls++
                AttriaxAttestationChallenge(nonce = "server_nonce", expiresInSeconds = 120)
            },
        )

        val envelope = manager.resolveEnvelope()

        assertTrue(manager.isEnabled)
        assertEquals(1, challengeCalls)
        assertEquals("server_nonce", provider.lastNonce)
        // Envelope wire shape: {provider, token, nonce}; keyId omitted on Android.
        assertEquals(
            mapOf(
                "provider" to "play_integrity",
                "token" to "integrity_token",
                "nonce" to "server_nonce",
            ),
            envelope,
        )
        assertFalse(envelope!!.containsKey("keyId"))
    }

    // -------- AT2: provider returns null → no envelope --------

    @Test
    fun enabledProviderReturnsNullResolvesToNull() {
        val manager = AttriaxAttestationManager(
            enabled = true,
            provider = AttriaxAttestationProvider { null },
            fetchChallenge = { AttriaxAttestationChallenge(nonce = "server_nonce") },
        )
        assertNull(manager.resolveEnvelope())
    }

    // -------- AT2: provider THROWS → null (never propagates) --------

    @Test
    fun enabledProviderThrowsResolvesToNull() {
        val manager = AttriaxAttestationManager(
            enabled = true,
            provider = AttriaxAttestationProvider { throw IllegalStateException("play services down") },
            fetchChallenge = { AttriaxAttestationChallenge(nonce = "server_nonce") },
        )
        assertNull(manager.resolveEnvelope())
    }

    // -------- AT2: challenge fetch returns null → null, provider never called --------

    @Test
    fun challengeNullSkipsProviderAndResolvesToNull() {
        val provider = FakeProvider { AttriaxAttestationToken(token = "t") }
        val manager = AttriaxAttestationManager(
            enabled = true,
            provider = provider,
            fetchChallenge = { null },
        )
        assertNull(manager.resolveEnvelope())
        assertEquals(0, provider.calls)
    }

    // -------- AT2: challenge fetch THROWS → null (never propagates) --------

    @Test
    fun challengeThrowsResolvesToNull() {
        val provider = FakeProvider { AttriaxAttestationToken(token = "t") }
        val manager = AttriaxAttestationManager(
            enabled = true,
            provider = provider,
            fetchChallenge = { throw RuntimeException("challenge endpoint unreachable") },
        )
        assertNull(manager.resolveEnvelope())
        assertEquals(0, provider.calls)
    }

    // -------- AT2: blank nonce → null, provider never called --------

    @Test
    fun blankNonceSkipsProviderAndResolvesToNull() {
        val provider = FakeProvider { AttriaxAttestationToken(token = "t") }
        val manager = AttriaxAttestationManager(
            enabled = true,
            provider = provider,
            fetchChallenge = { AttriaxAttestationChallenge(nonce = "   ") },
        )
        assertNull(manager.resolveEnvelope())
        assertEquals(0, provider.calls)
    }

    // -------- AT1/config: disabled → challenge never called, no envelope --------

    @Test
    fun disabledNeverFetchesChallengeAndResolvesToNull() {
        var challengeCalls = 0
        val provider = FakeProvider { AttriaxAttestationToken(token = "t") }
        val manager = AttriaxAttestationManager(
            enabled = false,
            provider = provider,
            fetchChallenge = { challengeCalls++; AttriaxAttestationChallenge(nonce = "n") },
        )
        assertFalse(manager.isEnabled)
        assertNull(manager.resolveEnvelope())
        assertEquals(0, challengeCalls)
        assertEquals(0, provider.calls)
    }

    // -------- config: enabled with null provider → noop → null --------

    @Test
    fun enabledWithNullProviderDefaultsToNoopAndResolvesToNull() {
        val manager = AttriaxAttestationManager(
            enabled = true,
            provider = null,
            fetchChallenge = { AttriaxAttestationChallenge(nonce = "server_nonce") },
        )
        assertTrue(manager.isEnabled)
        assertNull(manager.resolveEnvelope())
    }

    // -------- envelope: keyId included only when the provider supplies it --------

    @Test
    fun keyIdIncludedWhenProviderSuppliesIt() {
        val manager = AttriaxAttestationManager(
            enabled = true,
            provider = AttriaxAttestationProvider {
                AttriaxAttestationToken(token = "tok", keyId = "key_abc")
            },
            fetchChallenge = { AttriaxAttestationChallenge(nonce = "server_nonce") },
        )
        assertEquals(
            mapOf(
                "provider" to "play_integrity",
                "token" to "tok",
                "nonce" to "server_nonce",
                "keyId" to "key_abc",
            ),
            manager.resolveEnvelope(),
        )
    }

    // -------- nonce is trimmed before it reaches the provider + envelope --------

    @Test
    fun nonceIsTrimmed() {
        val provider = FakeProvider { AttriaxAttestationToken(token = "t") }
        val manager = AttriaxAttestationManager(
            enabled = true,
            provider = provider,
            fetchChallenge = { AttriaxAttestationChallenge(nonce = "  server_nonce  ") },
        )
        val envelope = manager.resolveEnvelope()
        assertEquals("server_nonce", provider.lastNonce)
        assertEquals("server_nonce", envelope!!["nonce"])
    }
}
