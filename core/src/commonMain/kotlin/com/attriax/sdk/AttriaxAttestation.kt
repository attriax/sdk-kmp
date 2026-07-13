package com.attriax.sdk

/**
 * Public device-attestation seam.
 *
 * Mirrors the Flutter reference contract
 * (`attriax_flutter_platform_interface/lib/src/types_attestation.dart`):
 *  - the provider slugs the server accepts,
 *  - the [AttriaxAttestationProvider] object seam the integration supplies,
 *  - the [AttriaxAttestationToken] a provider returns,
 *  - the shipped [NoopAttestationProvider] default (always `null` → no attestation).
 *
 * The whole flow is INERT unless [AttriaxConfig.attestationEnabled] is `true` and a
 * real provider is supplied via [AttriaxConfig.attestationProvider]; a `null`
 * provider degrades to the noop and no envelope is ever attached.
 */

/**
 * Canonical Attriax device-attestation provider slugs (server contract).
 *
 * The server treats any other/absent value as `attestation_missing`, so the SDK
 * only ever emits these two slugs. Android produces [PLAY_INTEGRITY]; Apple
 * platforms produce [APP_ATTEST] (not reachable from this Android core).
 */
object AttriaxAttestationProviderSlug {
    /** Android Play Integrity attestation. */
    const val PLAY_INTEGRITY: String = "play_integrity"

    /** Apple App Attest attestation (Apple-only; here for symmetry with the server). */
    const val APP_ATTEST: String = "app_attest"
}

/**
 * The token a native attestation provider produces for a server-issued nonce.
 *
 * [keyId] is App-Attest-only and is therefore always `null` for Play Integrity on
 * Android; the envelope omits it entirely when `null` (the server DTO makes every
 * sub-field optional and rejects unknown properties). The provider slug is not
 * carried here — the [AttriaxAttestationManager] stamps `play_integrity` and the
 * SDK-issued `nonce` when it assembles the envelope, so a provider only ever
 * returns the opaque OS [token] (plus a `keyId` on Apple).
 */
data class AttriaxAttestationToken(
    /** The OS attestation token/blob obtained from the native provider. */
    val token: String,
    /** App Attest key id. Always `null` for Play Integrity on Android. */
    val keyId: String? = null,
)

/**
 * Produces an [AttriaxAttestationToken] for a server-issued `nonce`.
 *
 * Implementations acquire a platform attestation token (Play Integrity on Android)
 * that embeds the `nonce`, then return it.
 *
 * Returning `null` is a first-class, expected outcome: it means attestation is
 * unavailable on this device (no Play Services, an OS error, a stub build). The SDK
 * then sends the app-open/init request with NO envelope. A well-behaved provider
 * degrades to `null` rather than throwing — but the [AttriaxAttestationManager]
 * catches any thrown error defensively regardless (attestation must never break
 * init).
 *
 * `attest` performs blocking I/O (Play Integrity is a network round-trip) and is
 * invoked off the main thread by the SDK's init bootstrap.
 */
fun interface AttriaxAttestationProvider {
    /** Attempts to attest against [nonce]. Returns `null` when unavailable. */
    fun attest(nonce: String): AttriaxAttestationToken?
}

/**
 * The shipped default provider: always returns `null` (no attestation).
 *
 * This is what an SDK instance uses unless the integration explicitly opts in
 * ([AttriaxConfig.attestationEnabled] = `true`) and supplies a real provider,
 * guaranteeing that attestation is inert by default.
 */
object NoopAttestationProvider : AttriaxAttestationProvider {
    override fun attest(nonce: String): AttriaxAttestationToken? = null
}
