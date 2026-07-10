package com.attriax.sdk.internal

import attriax.bcrypt.ax_secure_random_bytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlin.random.Random

/**
 * Windows actual for the secure-random seam backing [AttriaxIdGenerator].
 *
 * Uses the OS CSPRNG `BCryptGenRandom` (CNG, bcrypt.dll) with the
 * system-preferred RNG — cryptographically strong bytes for the 128-bit
 * device/session ids, replacing the desktop stopgap `Random.Default`. The Win32
 * call is wrapped by the `ax_secure_random_bytes` C shim (see `bcrypt.def`). On
 * the (practically never) chance the call reports a non-success `NTSTATUS`, fall
 * back to the stdlib RNG so id generation never throws and init is never broken
 * — the ids are opaque to the backend (only uniqueness + shape matter).
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxSecureRandomBytes(size: Int): ByteArray {
    if (size <= 0) return ByteArray(0)
    val out = ByteArray(size)
    val status = out.usePinned { pinned ->
        ax_secure_random_bytes(pinned.addressOf(0).reinterpret(), size)
    }
    // NTSTATUS 0 == STATUS_SUCCESS.
    if (status != 0) return Random.Default.nextBytes(size)
    return out
}
