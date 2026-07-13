package com.attriax.sdk.internal.attestation

import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.request.AttriaxEndpoints

/**
 * Fetches a single-use attestation nonce from `POST /api/sdk/attestation/challenge`.
 * Pure over the [HttpClient] port so the attestation flow can be
 * unit-tested with a fake transport.
 *
 * Mirrors the Flutter `fetchAttestationChallenge`
 * (`attriax_generated_transport.dart:378-404`): any non-success status, a missing
 * body, or a missing/blank `nonce` yields `null` (→ the SDK sends the open with no
 * envelope). Network-level exceptions from the transport are NOT swallowed here —
 * the [AttriaxAttestationManager] catches them, treating a challenge outage as "no
 * nonce" so it can never break init.
 *
 * The challenge body carries no request fields (the api endpoint is `@Public()`
 * and takes no payload), so an empty JSON object is posted.
 */
class AttriaxAttestationChallengeFetcher(
    private val transport: HttpClient,
) {
    fun fetch(): AttriaxAttestationChallenge? {
        val response = transport.post(AttriaxEndpoints.ATTESTATION_CHALLENGE, "{}")
        val body = response.body ?: return null

        val decoded = try {
            Json.decode(body)
        } catch (e: Exception) {
            return null
        } as? Map<*, *> ?: return null

        val nonce = (decoded["nonce"] as? String)?.trim()
        if (nonce.isNullOrEmpty()) return null

        val expiresInSeconds = (decoded["expiresInSeconds"] as? Number)?.toInt()
        return AttriaxAttestationChallenge(nonce = nonce, expiresInSeconds = expiresInSeconds)
    }
}
