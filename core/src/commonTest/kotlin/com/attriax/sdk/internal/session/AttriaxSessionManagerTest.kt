package com.attriax.sdk.internal.session

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers continue-vs-new, heartbeat interval selection, and snapshot
 * persist/restore/revalidate + recovered-end. The clock is injected;
 * no wall-clock sleeps.
 */
class AttriaxSessionManagerTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    private class MutableClock(var value: Long) : AttriaxClock {
        override fun nowMs(): Long = value
    }

    private val heartbeat = 5 * 60_000L        // 5min → 10min window
    private val firstLaunchHeartbeat = 30_000L // 30s → clamped to 60s floor window

    private var seq = 0
    private fun newManager(store: MapStore, clock: AttriaxClock) = AttriaxSessionManager(
        clock = clock,
        snapshotStore = AttriaxSessionSnapshotStore(store),
        heartbeatIntervalMs = heartbeat,
        firstLaunchHeartbeatIntervalMs = firstLaunchHeartbeat,
        generateSessionId = { "sess-${seq++}" },
    )

    private fun identity(
        deviceId: String? = "d1",
        appVersion: String? = "1.0",
        isFirstLaunch: Boolean = false,
    ) = AttriaxSessionIdentity(
        deviceId = deviceId,
        platform = "android",
        appPackageName = "com.example",
        appVersion = appVersion,
        appBuildNumber = "1",
        locale = "en-US",
        isFirstLaunch = isFirstLaunch,
        sdkPackageVersion = "0.6.0",
    )

    // ---- S3: heartbeat interval selection ----

    @Test
    fun firstLaunchSessionUsesFirstLaunchHeartbeatInterval() {
        val clock = MutableClock(1_000L)
        val manager = newManager(MapStore(), clock)

        val result = manager.restoreOrStart(identity(isFirstLaunch = true))

        assertTrue(result.startedNewSession)
        assertEquals(firstLaunchHeartbeat, result.currentSession.heartbeatIntervalMs)
    }

    @Test
    fun returningLaunchSessionUsesStandardHeartbeatInterval() {
        val clock = MutableClock(1_000L)
        val manager = newManager(MapStore(), clock)

        val result = manager.restoreOrStart(identity(isFirstLaunch = false))

        assertEquals(heartbeat, result.currentSession.heartbeatIntervalMs)
    }

    // ---- S2/S5: continue-vs-new on restore ----

    @Test
    fun restoreContinuesSameSessionWithinWindowAndBumpsActivity() {
        val store = MapStore()
        val clock = MutableClock(1_000L)
        val first = newManager(store, clock).restoreOrStart(identity())
        val originalId = first.currentSession.sessionId

        // Restore 9min later (< 10min window) in a fresh manager reading the snapshot.
        clock.value = 1_000L + 9L * 60_000
        val result = newManager(store, clock).restoreOrStart(identity())

        assertFalse(result.startedNewSession)
        assertEquals(originalId, result.currentSession.sessionId)
        assertEquals(clock.value, result.currentSession.lastActivityAtMs)
        assertNull(result.replacedSession)
    }

    @Test
    fun restorePastWindowStartsNewSessionAndReportsReplacedForRecoveredEnd() {
        val store = MapStore()
        val clock = MutableClock(1_000L)
        val first = newManager(store, clock).restoreOrStart(identity())
        val staleId = first.currentSession.sessionId

        // Restore 11min later (> 10min window).
        clock.value = 1_000L + 11L * 60_000
        val result = newManager(store, clock).restoreOrStart(identity())

        assertTrue(result.startedNewSession)
        assertTrue(result.currentSession.sessionId != staleId)
        assertEquals(staleId, result.replacedSession?.sessionId)
    }

    @Test
    fun restoreStartsNewOnIdentityDriftAndReplacesOld() {
        val store = MapStore()
        val clock = MutableClock(1_000L)
        val first = newManager(store, clock).restoreOrStart(identity(appVersion = "1.0"))

        clock.value = 2_000L // well within window; only identity differs
        val result = newManager(store, clock).restoreOrStart(identity(appVersion = "2.0"))

        assertTrue(result.startedNewSession)
        assertEquals(first.currentSession.sessionId, result.replacedSession?.sessionId)
    }

    // ---- resume (foreground from background) ----

    @Test
    fun resumeWithinWindowKeepsSameSessionId() {
        val store = MapStore()
        val clock = MutableClock(1_000L)
        val manager = newManager(store, clock)
        val started = manager.restoreOrStart(identity())

        val resumed = manager.resumeOrStart(identity(), atMs = 1_000L + 5L * 60_000)

        assertFalse(resumed.startedNewSession)
        assertEquals(started.currentSession.sessionId, resumed.currentSession.sessionId)
    }

    @Test
    fun resumePastWindowStartsNewSessionWithRecoveredEndForOld() {
        val store = MapStore()
        val clock = MutableClock(1_000L)
        val manager = newManager(store, clock)
        val started = manager.restoreOrStart(identity())

        val resumed = manager.resumeOrStart(identity(), atMs = 1_000L + 20L * 60_000)

        assertTrue(resumed.startedNewSession)
        assertEquals(started.currentSession.sessionId, resumed.replacedSession?.sessionId)
    }

    // ---- activity / end ----

    @Test
    fun recordActivityIsMonotonicAndPersists() {
        val store = MapStore()
        val clock = MutableClock(1_000L)
        val manager = newManager(store, clock)
        manager.restoreOrStart(identity())

        val bumped = manager.recordActivity(5_000L)
        assertEquals(5_000L, bumped?.lastActivityAtMs)
        // An earlier timestamp is ignored (out-of-order).
        val ignored = manager.recordActivity(3_000L)
        assertEquals(5_000L, ignored?.lastActivityAtMs)
        // Persisted value reflects the latest.
        assertEquals(5_000L, AttriaxSessionSnapshotStore(store).read()?.lastActivityAtMs)
    }

    @Test
    fun endClearsSessionFromMemoryAndStorage() {
        val store = MapStore()
        val clock = MutableClock(1_000L)
        val manager = newManager(store, clock)
        manager.restoreOrStart(identity())

        val ended = manager.end(9_000L)

        assertEquals(9_000L, ended?.lastActivityAtMs)
        assertNull(manager.currentSession)
        assertNull(AttriaxSessionSnapshotStore(store).read())
    }

    @Test
    fun inferredRecoveredEndClampsToNow() {
        val store = MapStore()
        val clock = MutableClock(1_000L)
        val manager = newManager(store, clock)
        val started = manager.restoreOrStart(identity())
        val session = started.currentSession // lastActivity=1_000, window=10min

        // now is BEFORE projected end (1_000 + 10min) → clamp to now.
        clock.value = 1_000L + 3L * 60_000
        assertEquals(clock.value, manager.inferredRecoveredEndAtMs(session))

        // now is AFTER projected end → use projected end.
        clock.value = 1_000L + 30L * 60_000
        assertEquals(1_000L + 10L * 60_000, manager.inferredRecoveredEndAtMs(session))
    }
}
