package com.attriax.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** config defaults. */
class AttriaxConfigTest {

    @Test
    fun defaultsMatchParityContract() {
        val config = AttriaxConfig(projectToken = "tok")

        assertEquals("https://api.attriax.com", config.apiBaseUrl)
        assertEquals(12_000L, config.requestTimeoutMs)
        assertEquals(500, config.maxQueueSize)
        assertEquals(60_000L, config.eventFlushIntervalMs)
        assertEquals(5 * 60_000L, config.sessionHeartbeatIntervalMs)
        assertEquals(30_000L, config.firstLaunchSessionHeartbeatIntervalMs)

        assertTrue(config.flushEventsImmediatelyOnFirstLaunch)
        assertTrue(config.collectAdvertisingId)
        assertTrue(config.automaticCrashReportingEnabled)
        assertTrue(config.anonymousTracking)
        assertTrue(config.sessionTrackingEnabled)

        assertFalse(config.gdprEnabled)
        assertFalse(config.attestationEnabled)
        assertEquals(null, config.attestationProvider)
        assertTrue(config.pinnedCertificateSha256Fingerprints.isEmpty())
    }

    @Test
    fun projectTokenIsTrimmed() {
        assertEquals("tok", AttriaxConfig(projectToken = "  tok  ").normalizedProjectToken)
    }

    @Test
    fun rejectsNonPositiveQueueSize() {
        assertFailsWith<IllegalArgumentException> {
            AttriaxConfig(projectToken = "tok", maxQueueSize = 0)
        }
    }
}
