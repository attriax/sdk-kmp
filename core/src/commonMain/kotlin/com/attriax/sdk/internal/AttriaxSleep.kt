package com.attriax.sdk.internal

/**
 * Platform blocking-sleep seam. Backs the install-referrer coordinator's single
 * capture retry pause (replaces the JVM `Thread.sleep`). Injected/overridable in
 * tests, so it never actually sleeps there.
 */
internal expect fun attriaxSleep(ms: Long)
