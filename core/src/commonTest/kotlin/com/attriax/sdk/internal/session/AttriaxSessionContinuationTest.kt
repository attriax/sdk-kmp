package com.attriax.sdk.internal.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Continuation window math + lifecycle kinds. */
class AttriaxSessionContinuationTest {

    private val ctx = AttriaxSessionContext(
        deviceId = "d1", platform = "android",
        appPackageName = "p", appVersion = "1.0", appBuildNumber = "1",
    )

    private fun snapshot(
        heartbeatMs: Long,
        lastActivityMs: Long,
        startedMs: Long = 0L,
        deviceId: String? = "d1",
        platform: String = "android",
        appVersion: String? = "1.0",
    ) = AttriaxSessionSnapshot(
        sessionId = "s1", startedAtMs = startedMs, lastActivityAtMs = lastActivityMs,
        heartbeatIntervalMs = heartbeatMs, deviceId = deviceId, platform = platform,
        appPackageName = "p", appVersion = appVersion, appBuildNumber = "1",
    )

    // ---- S2: window = 2 x heartbeat, clamped [60s, 30min] ----

    @Test
    fun windowIsTwiceHeartbeatWithinClamp() {
        // 5min heartbeat → 10min window (within clamp).
        assertEquals(10L * 60_000, AttriaxSessionContinuation.continuationWindowMs(5 * 60_000L))
    }

    @Test
    fun windowClampsToMinFloor() {
        // 30s first-launch heartbeat → 60s raw, exactly the floor.
        assertEquals(60_000L, AttriaxSessionContinuation.continuationWindowMs(30_000L))
        // 10s heartbeat → 20s raw → clamped up to 60s floor.
        assertEquals(60_000L, AttriaxSessionContinuation.continuationWindowMs(10_000L))
    }

    @Test
    fun windowClampsToMaxCeiling() {
        // 20min heartbeat → 40min raw → clamped down to 30min.
        assertEquals(30L * 60_000, AttriaxSessionContinuation.continuationWindowMs(20 * 60_000L))
    }

    // ---- continuation decision ----

    @Test
    fun continuesWhenWithinWindowAndIdentityMatches() {
        val hb = 5 * 60_000L // window 10min
        val snap = snapshot(heartbeatMs = hb, lastActivityMs = 0L)
        assertTrue(AttriaxSessionContinuation.shouldContinue(snap, ctx, nowMs = 9L * 60_000))
    }

    @Test
    fun startsNewWhenBeyondWindow() {
        val hb = 5 * 60_000L
        val snap = snapshot(heartbeatMs = hb, lastActivityMs = 0L)
        assertFalse(AttriaxSessionContinuation.shouldContinue(snap, ctx, nowMs = 11L * 60_000))
    }

    @Test
    fun startsNewOnIdentityDrift() {
        val hb = 5 * 60_000L
        assertFalse(
            AttriaxSessionContinuation.shouldContinue(
                snapshot(hb, 0L, deviceId = "other"), ctx, nowMs = 1_000,
            ),
        )
        assertFalse(
            AttriaxSessionContinuation.shouldContinue(
                snapshot(hb, 0L, platform = "ios"), ctx, nowMs = 1_000,
            ),
        )
        assertFalse(
            AttriaxSessionContinuation.shouldContinue(
                snapshot(hb, 0L, appVersion = "2.0"), ctx, nowMs = 1_000,
            ),
        )
    }

    @Test
    fun startsNewWhenSnapshotAbsent() {
        assertFalse(AttriaxSessionContinuation.shouldContinue(null, ctx, nowMs = 0L))
    }

    @Test
    fun startsNewWhenStartInFuture() {
        val snap = snapshot(heartbeatMs = 5 * 60_000L, lastActivityMs = 0L, startedMs = 10_000L)
        assertFalse(AttriaxSessionContinuation.shouldContinue(snap, ctx, nowMs = 5_000L))
    }

    // ---- S3: lifecycle kinds ----

    @Test
    fun lifecycleKindsMatchContract() {
        assertEquals(
            listOf("start", "heartbeat", "pause", "resume", "end"),
            AttriaxSessionContinuation.Lifecycle.ALL,
        )
    }
}
