package com.attriax.sdk.internal.attestation

import com.attriax.sdk.AttriaxAttestationProvider
import com.attriax.sdk.AttriaxAttestationProviderSlug
import com.attriax.sdk.NoopAttestationProvider

/**
 * The single-use challenge issued by `POST /api/sdk/attestation/challenge`.
 * Wire shape mirrors the api `AttestationChallengeResponseDto`:
 * `{ nonce, expiresInSeconds }` (only `nonce` is load-bearing; the SDK does not
 * currently act on the TTL beyond carrying it).
 */
data class AttriaxAttestationChallenge(
    val nonce: String,
    val expiresInSeconds: Int? = null,
)

/**
 * Orchestrates the SDK-side device-attestation flow (
 * Flutter reference `attriax_attestation_manager.dart:30-91`).
 *
 * Enabled only when [com.attriax.sdk.AttriaxConfig.attestationEnabled] is `true`.
 * When enabled, [resolveEnvelope] fetches a nonce from the challenge endpoint,
 * asks the configured provider to produce an attestation token, and returns the
 * assembled envelope map for attachment to the app-open/init request under
 * `attestation`.
 *
 * The whole flow is best-effort and defensive (critical): a disabled
 * config, a failed/`null` challenge fetch, a `null` provider result, an
 * unavailable/throwing provider, or ANY thrown error all resolve to `null`, which
 * means the open is sent with NO envelope. Attestation must NEVER block or fail
 * init — this mirrors the server's "never break the install" invariant.
 *
 * This class is PURE (no Android / Play Services types): the challenge fetch is a
 * `() -> AttriaxAttestationChallenge?` seam and the provider is the public
 * object interface, so the flow is fully unit-testable with fakes. The real Play
 * Integrity call lives behind the provider in the android layer.
 */
class AttriaxAttestationManager(
    enabled: Boolean,
    provider: AttriaxAttestationProvider?,
    private val fetchChallenge: () -> AttriaxAttestationChallenge?,
) {
    private val enabled: Boolean = enabled
    private val provider: AttriaxAttestationProvider = provider ?: NoopAttestationProvider

    /** Whether attestation is opted in for this SDK instance. */
    val isEnabled: Boolean get() = enabled

    /**
     * Resolves the attestation envelope to attach to the app-open/init request.
     *
     * Returns `null` (→ attach nothing) when attestation is disabled, the challenge
     * could not be fetched, the provider returned `null`, or any error occurred.
     * Never throws.
     *
     * Performs blocking I/O (challenge fetch + provider attest) — call off the main
     * thread. The SDK invokes it on its flush executor during init bootstrap.
     */
    fun resolveEnvelope(): Map<String, Any?>? {
        if (!enabled) return null

        return try {
            val challenge = fetchChallenge() ?: return null
            val nonce = challenge.nonce.trim()
            if (nonce.isEmpty()) return null

            val token = provider.attest(nonce) ?: return null

            // The provider slug is stamped by the SDK (Android → play_integrity), not
            // returned by the provider; the nonce always comes from the SDK-issued
            // challenge so the server can match the single-use value it issued. keyId
            // is App-Attest-only, so it is omitted on Android unless a provider echoes
            // one back (it never does for Play Integrity).
            val envelope = LinkedHashMap<String, Any?>()
            envelope["provider"] = AttriaxAttestationProviderSlug.PLAY_INTEGRITY
            envelope["token"] = token.token
            envelope["nonce"] = nonce
            token.keyId?.let { envelope["keyId"] = it }
            envelope
        } catch (e: Throwable) {
            // Attestation is best-effort — never let it break init.
            null
        }
    }
}
