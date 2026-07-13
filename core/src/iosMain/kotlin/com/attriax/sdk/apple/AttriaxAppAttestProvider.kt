package com.attriax.sdk.apple

import com.attriax.sdk.AttriaxAttestationProvider
import com.attriax.sdk.AttriaxAttestationToken
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
        val digest = sha256Digest(input.encodeToByteArray())
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

// Dependency-free SHA-256 (FIPS 180-4) producing the 32-byte digest App Attest signs
// as its clientDataHash. Kept local (no CommonCrypto cinterop dependency) so the file
// compiles across the Apple platform libs; the server recomputes SHA256(nonce) to
// verify the attestation binds the exact nonce it issued.
private val SHA256_K = intArrayOf(
    0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
    -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
    -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
    -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
)

private fun sha256Digest(message: ByteArray): ByteArray {
    var h0 = 0x6a09e667; var h1 = -0x4498517b; var h2 = 0x3c6ef372; var h3 = -0x5ab00ac6
    var h4 = 0x510e527f; var h5 = -0x64fa9774; var h6 = 0x1f83d9ab; var h7 = 0x5be0cd19

    val originalBitLen = message.size.toLong() * 8
    // Pad: 0x80, then zeros to 56 mod 64, then 8-byte big-endian bit length.
    val paddedLen = (((message.size + 8) / 64) + 1) * 64
    val padded = ByteArray(paddedLen)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[paddedLen - 1 - i] = ((originalBitLen ushr (8 * i)) and 0xff).toByte()
    }

    val w = IntArray(64)
    var offset = 0
    while (offset < paddedLen) {
        for (i in 0 until 16) {
            w[i] = ((padded[offset + i * 4].toInt() and 0xff) shl 24) or
                ((padded[offset + i * 4 + 1].toInt() and 0xff) shl 16) or
                ((padded[offset + i * 4 + 2].toInt() and 0xff) shl 8) or
                (padded[offset + i * 4 + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = (w[i - 15].rotateRight(7)) xor (w[i - 15].rotateRight(18)) xor (w[i - 15] ushr 3)
            val s1 = (w[i - 2].rotateRight(17)) xor (w[i - 2].rotateRight(19)) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = h0; var b = h1; var c = h2; var d = h3
        var e = h4; var f = h5; var g = h6; var h = h7
        for (i in 0 until 64) {
            val s1 = (e.rotateRight(6)) xor (e.rotateRight(11)) xor (e.rotateRight(25))
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + SHA256_K[i] + w[i]
            val s0 = (a.rotateRight(2)) xor (a.rotateRight(13)) xor (a.rotateRight(22))
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            h = g; g = f; f = e; e = d + temp1; d = c; c = b; b = a; a = temp1 + temp2
        }
        h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
        offset += 64
    }

    val out = ByteArray(32)
    intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).forEachIndexed { idx, hv ->
        out[idx * 4] = (hv ushr 24).toByte()
        out[idx * 4 + 1] = (hv ushr 16).toByte()
        out[idx * 4 + 2] = (hv ushr 8).toByte()
        out[idx * 4 + 3] = hv.toByte()
    }
    return out
}
