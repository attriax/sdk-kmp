package com.attriax.sdk.android

import android.content.Context
import com.attriax.sdk.AttriaxAttestationProvider
import com.attriax.sdk.AttriaxAttestationToken
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import com.google.android.gms.tasks.Tasks

/**
 * Play Integrity attestation provider (PARITY §9, slug `play_integrity`).
 *
 * This is the ONLY place the real Play Integrity API is touched, keeping the
 * flow/envelope logic ([com.attriax.sdk.internal.attestation.AttriaxAttestationManager])
 * pure and JVM-tested with a fake provider. Here we call the OS with the
 * server-issued nonce and return the opaque integrity token; `keyId` is
 * App-Attest-only and is always `null` on Android.
 *
 * ## Dependency posture (compileOnly)
 * `com.google.android.play:integrity` is a **compileOnly** dependency of the base
 * SDK so the artifact stays lean for the vast majority of integrations that never
 * enable attestation. An integration that opts into Play Integrity MUST add the
 * runtime dependency itself:
 *
 * ```kotlin
 * dependencies {
 *     implementation("com.google.android.play:integrity:1.4.0")
 * }
 * ```
 *
 * Because the class is only referenced when the integration explicitly constructs
 * this provider and passes it to [com.attriax.sdk.AttriaxConfig.attestationProvider],
 * an integration that does NOT enable attestation never loads these classes and
 * never needs the runtime dependency. If the runtime dependency is somehow absent
 * while attestation IS enabled, [attest] catches the resulting
 * `NoClassDefFoundError`/`LinkageError` (via the `Throwable` catch below) and
 * degrades to `null` — the SDK then sends the open with no envelope rather than
 * crashing init (row AT2).
 *
 * ## Real token minting is device-only
 * `requestIntegrityToken` requires Google Play Services and a configured Play
 * Console project; it cannot mint a real token in a JVM unit test. This class is
 * therefore code-complete but device-verified only.
 *
 * @param cloudProjectNumber the Google Cloud project number backing the Play
 *   Console app. Optional for the standard (non-classic) request flow; supply it
 *   when the app requires it.
 */
class AttriaxPlayIntegrityAttestationProvider(
    context: Context,
    private val cloudProjectNumber: Long? = null,
) : AttriaxAttestationProvider {

    private val appContext: Context = context.applicationContext

    override fun attest(nonce: String): AttriaxAttestationToken? {
        val trimmed = nonce.trim()
        if (trimmed.isEmpty()) return null

        return try {
            val manager = IntegrityManagerFactory.create(appContext)
            val requestBuilder = IntegrityTokenRequest.builder().setNonce(trimmed)
            cloudProjectNumber?.let { requestBuilder.setCloudProjectNumber(it) }

            // Blocking await: the SDK invokes providers off the main thread during
            // init bootstrap, so a synchronous await is safe and keeps the provider
            // interface simple (nonce → token).
            val response: IntegrityTokenResponse =
                Tasks.await(manager.requestIntegrityToken(requestBuilder.build()))

            val token = response.token()?.trim()
            if (token.isNullOrEmpty()) null else AttriaxAttestationToken(token = token)
        } catch (e: Throwable) {
            // Play Integrity unavailable / errored / dependency missing → degrade to
            // null so attestation never breaks init (row AT2). Includes
            // NoClassDefFoundError when the runtime dependency is absent.
            null
        }
    }
}
