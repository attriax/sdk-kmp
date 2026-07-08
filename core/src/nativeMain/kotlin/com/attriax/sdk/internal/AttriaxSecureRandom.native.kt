package com.attriax.sdk.internal

import kotlin.random.Random

// INTERIM: Kotlin/Native has no stdlib CSPRNG. Random.Default gives adequate
// 128-bit uniqueness for opaque ids; harden to a platform OS CSPRNG
// (BCryptGenRandom on Windows / getrandom on Linux) via cinterop as a follow-up.
internal actual fun attriaxSecureRandomBytes(size: Int): ByteArray =
    Random.Default.nextBytes(size)
