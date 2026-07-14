package com.attriax.sdk.internal.attestation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Known-answer tests (KAT) for the hand-rolled FIPS 180-4 SHA-256 used on the Apple
 * App Attest `clientDataHash` path ([attriaxSha256]). The digests below are the exact
 * NIST published vectors; because the server recomputes `SHA256(nonce)` to verify the
 * attestation binds the issued nonce, any drift in this implementation would silently
 * break attestation — so it is pinned here.
 */
class AttriaxSha256Test {

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun digestHex(input: String): String =
        attriaxSha256(input.encodeToByteArray()).toHex()

    @Test
    fun emptyString() {
        // NIST: SHA-256("")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digestHex(""),
        )
    }

    @Test
    fun abc() {
        // NIST: SHA-256("abc") — single-block message.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digestHex("abc"),
        )
    }

    @Test
    fun multiBlock() {
        // NIST: SHA-256 of the 56-byte two-block message (padding crosses the 64-byte
        // block boundary, exercising the multi-block loop).
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            digestHex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }
}
