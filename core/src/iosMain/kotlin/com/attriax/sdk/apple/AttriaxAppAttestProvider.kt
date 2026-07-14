package com.attriax.sdk.apple

import com.attriax.sdk.AttriaxAttestationProvider
import com.attriax.sdk.AttriaxAttestationToken
import com.attriax.sdk.internal.attestation.attriaxSha256
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.DeviceCheck.DCAppAttestService
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUserDefaults
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * App Attest attestation provider (slug `app_attest`), implementing the
 * public [AttriaxAttestationProvider] seam. Supply an instance to
 * `AttriaxConfig.attestationProvider` with `attestationEnabled = true` to opt in.
 *
 * This is the ONLY place the real `DCAppAttestService` API is touched, keeping the
 * envelope logic (`AttriaxAttestationManager`) pure and testable with a fake. Given
 * the server-issued nonce, it attests the device key and returns the opaque
 * base64 attestation object + the `keyId`.
 *
 * ## Never breaks init
 * `attest` is fully defensive: an unsupported device (`isSupported == false`, e.g. the
 * Simulator or a device without the Secure Enclave), a key-generation / attestation
 * error, or any thrown error degrades to `null` so the SDK sends the app-open with no
 * envelope rather than crashing. The generated App Attest key id is
 * persisted (SDK `NSUserDefaults` suite) and reused across launches — App Attest keys
 * are device-and-app bound and expensive to mint; a failed attestation forgets the
 * key so the next launch mints a fresh one.
 *
 * ## Device-only
 * `DCAppAttestService.attestKey` needs a real device (Secure Enclave + a round-trip to
 * Apple); it cannot mint a real object in the Simulator (`isSupported` is false there).
 * This type is therefore code-complete but device-verified only.
 *
 * ## nonce → clientDataHash
 * App Attest signs a 32-byte `clientDataHash`; we derive it as `SHA256(nonce)` (the
 * conventional binding the server recomputes to verify the attestation binds the
 * exact nonce it issued).
 */
@OptIn(ExperimentalForeignApi::class)
class AttriaxAppAttestProvider(
    private val defaults: NSUserDefaults = NSUserDefaults(suiteName = SUITE_NAME)
        ?: NSUserDefaults.standardUserDefaults,
) : AttriaxAttestationProvider {

    private val service: DCAppAttestService? =
        if (isOperatingSystemAtLeast(14, 0)) DCAppAttestService.sharedService else null

    override fun attest(nonce: String): AttriaxAttestationToken? {
        val trimmed = nonce.trim()
        if (trimmed.isEmpty()) return null

        val service = this.service ?: return null
        // Unsupported hardware (Simulator, missing Secure Enclave) → degrade to null.
        if (!service.isSupported()) return null

        val keyId = try {
            resolveKeyId(service)
        } catch (e: Throwable) {
            return null
        }
        if (keyId.isEmpty()) return null

        val clientDataHash = sha256(trimmed)
        val attestation = attestKeyBlocking(service, keyId, clientDataHash)
        if (attestation == null || attestation.length.toLong() == 0L) {
            // A failed attestation may mean the persisted key is invalid on this
            // device/app version; forget it so the next launch mints a fresh one.
            forgetStoredKeyId()
            return null
        }

        val token = attestation.base64EncodedStringWithOptions(0u)
        return AttriaxAttestationToken(token = token, keyId = keyId)
    }

    // -------- key management --------

    private fun resolveKeyId(service: DCAppAttestService): String {
        loadStoredKeyId()?.takeIf { it.isNotEmpty() }?.let { return it }
        val generated = generateKeyBlocking(service)
        saveStoredKeyId(generated)
        return generated
    }

    private fun loadStoredKeyId(): String? = defaults.stringForKey(KEY_STORED_KEY_ID)

    private fun saveStoredKeyId(value: String) = defaults.setObject(value, forKey = KEY_STORED_KEY_ID)

    private fun forgetStoredKeyId() = defaults.removeObjectForKey(KEY_STORED_KEY_ID)

    /** Synchronous bridge over the async `generateKey` (blocking is safe off-main). */
    private fun generateKeyBlocking(service: DCAppAttestService): String {
        val keyId = atomic<String?>(null)
        val semaphore = dispatch_semaphore_create(0)
        service.generateKeyWithCompletionHandler { generated, _ ->
            keyId.value = generated
            dispatch_semaphore_signal(semaphore)
        }
        dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)
        return keyId.value ?: ""
    }

    /** Synchronous bridge over the async `attestKey`; null on error/empty. */
    private fun attestKeyBlocking(
        service: DCAppAttestService,
        keyId: String,
        clientDataHash: NSData,
    ): NSData? {
        val result = atomic<NSData?>(null)
        val semaphore = dispatch_semaphore_create(0)
        service.attestKey(keyId, clientDataHash = clientDataHash) { attestation, error ->
            if (error == null) result.value = attestation
            dispatch_semaphore_signal(semaphore)
        }
        dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)
        return result.value
    }

    /** SHA-256 of the nonce → the 32-byte `clientDataHash` the server recomputes. */
    private fun sha256(input: String): NSData {
        val digest = attriaxSha256(input.encodeToByteArray())
        return digest.usePinned {
            NSData.create(bytes = it.addressOf(0), length = digest.size.convert())
        }
    }

    companion object {
        private const val SUITE_NAME = "com.attriax.sdk.prefs"
        private const val KEY_STORED_KEY_ID = "attriax.app_attest.key_id"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun isOperatingSystemAtLeast(major: Int, minor: Int): Boolean {
    val version = kotlinx.cinterop.cValue<NSOperatingSystemVersion> {
        majorVersion = major.toLong()
        minorVersion = minor.toLong()
        patchVersion = 0
    }
    return NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(version)
}
