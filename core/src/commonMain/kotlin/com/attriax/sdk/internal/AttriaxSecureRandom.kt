package com.attriax.sdk.internal

/**
 * Platform random-bytes seam backing [AttriaxIdGenerator].
 *
 * JVM/Android back it with a cryptographic `SecureRandom`. The native desktop
 * targets currently use the (non-cryptographic) stdlib RNG — adequate because the
 * ids are opaque to the backend (only 128-bit uniqueness + shape matter) — pending
 * a real OS CSPRNG (BCryptGenRandom / getrandom) via cinterop.
 */
internal expect fun attriaxSecureRandomBytes(size: Int): ByteArray
