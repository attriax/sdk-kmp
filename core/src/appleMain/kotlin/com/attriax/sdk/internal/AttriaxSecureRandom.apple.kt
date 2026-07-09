package com.attriax.sdk.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlin.random.Random
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.Security.errSecSuccess

/**
 * Apple actual for the secure-random seam backing [AttriaxIdGenerator].
 *
 * Uses the OS CSPRNG `SecRandomCopyBytes` (Security.framework) — the same source the
 * standalone iOS SDK's id generator uses — so the 128-bit device/session ids are
 * cryptographically random on iOS/macOS rather than the desktop stopgap
 * `Random.Default`. On the (practically never) chance the syscall reports failure,
 * fall back to the stdlib RNG so id generation never throws and init is never broken
 * — the ids are opaque to the backend (only uniqueness + shape matter).
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxSecureRandomBytes(size: Int): ByteArray {
    if (size <= 0) return ByteArray(0)
    val out = ByteArray(size)
    val status = out.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
    }
    if (status != errSecSuccess) {
        return Random.Default.nextBytes(size)
    }
    return out
}
