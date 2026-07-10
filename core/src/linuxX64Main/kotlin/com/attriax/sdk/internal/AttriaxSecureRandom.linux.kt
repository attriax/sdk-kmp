package com.attriax.sdk.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.read
import kotlin.random.Random

/**
 * Linux actual for the secure-random seam backing [AttriaxIdGenerator].
 *
 * Reads the OS CSPRNG via `/dev/urandom` (universally available, non-blocking,
 * seeded by the kernel entropy pool) — cryptographically strong bytes for the
 * 128-bit device/session ids, replacing the desktop stopgap `Random.Default`.
 * Any failure (open/read error, short read) falls back to the stdlib RNG so id
 * generation never throws and init is never broken — the ids are opaque to the
 * backend (only uniqueness + shape matter).
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun attriaxSecureRandomBytes(size: Int): ByteArray {
    if (size <= 0) return ByteArray(0)
    val out = ByteArray(size)
    val fd = open("/dev/urandom", O_RDONLY)
    if (fd < 0) return Random.Default.nextBytes(size)
    try {
        out.usePinned { pinned ->
            var offset = 0
            while (offset < size) {
                val n = read(fd, pinned.addressOf(offset), (size - offset).convert()).toInt()
                if (n <= 0) return Random.Default.nextBytes(size)
                offset += n
            }
        }
    } finally {
        close(fd)
    }
    return out
}
