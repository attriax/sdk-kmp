package com.attriax.sdk.internal

import java.security.SecureRandom

private val secureRandom = SecureRandom()

internal actual fun attriaxSecureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    secureRandom.nextBytes(bytes)
    return bytes
}
