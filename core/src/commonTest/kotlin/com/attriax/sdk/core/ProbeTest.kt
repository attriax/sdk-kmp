package com.attriax.sdk.core

import kotlin.test.Test
import kotlin.test.assertTrue

class ProbeTest {
    @Test
    fun greetingIncludesPlatform() {
        assertTrue(Probe.greeting().startsWith("attriax-kmp on "))
    }
}
