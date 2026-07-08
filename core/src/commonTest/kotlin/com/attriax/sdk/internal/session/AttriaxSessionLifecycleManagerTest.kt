package com.attriax.sdk.internal.session

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxScheduler
import com.attriax.sdk.internal.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PARITY rows S3 (heartbeat timers + foreground/background/detach transitions),
 * S4 (keep-alive heartbeat build) and S5 (recovered-end on replace). Timers are
 * deterministic: a fake scheduler captures the periodic action and the test fires
 * ticks explicitly — no wall-clock sleeping.
 */
class AttriaxSessionLifecycleManagerTest {

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

    /** Captures the single periodic action; the test drives ticks + tracks cancels. */
    private class FakeScheduler : AttriaxScheduler {
        var scheduledIntervalMs: Long? = null
        var action: (() -> Unit)? = null
        var cancelCount = 0
        var activeSchedules = 0

        override fun schedulePeriodic(intervalMs: Long, action: () -> Unit): AttriaxScheduler.ScheduledHandle {
            scheduledIntervalMs = intervalMs
            this.action = action
            activeSchedules++
            return AttriaxScheduler.ScheduledHandle {
                cancelCount++
                activeSchedules--
                if (this.action === action) this.action = null
            }
        }

        override fun scheduleOnce(delayMs: Long, action: () -> Unit): AttriaxScheduler.ScheduledHandle {
            // The session-lifecycle manager only uses schedulePeriodic; the one-shot
            // deferred-flush seam is exercised by the engine tests, not here.
            return AttriaxScheduler.ScheduledHandle { }
        }

        val hasActiveTimer: Boolean get() = activeSchedules > 0
        fun tick() = action!!.invoke()
    }

    private val heartbeat = 5 * 60_000L

    private fun identity() = AttriaxSessionIdentity(
        deviceId = "d1", platform = "android", appPackageName = "com.example",
        appVersion = "1.0", appBuildNumber = "1", locale = "en-US",
        isFirstLaunch = false, sdkPackageVersion = "0.5.0",
    )

    private class Fixture {
        val store = MapStore()
        val clock = MutableClock(1_000L)
        val scheduler = FakeScheduler()
        val enqueued = ArrayList<AttriaxSessionLifecycleEvent>()
        var flushes = 0
        var enabled = true

        val sessionManager = AttriaxSessionManager(
            clock = clock,
            snapshotStore = AttriaxSessionSnapshotStore(store),
            heartbeatIntervalMs = 5 * 60_000L,
            firstLaunchHeartbeatIntervalMs = 30_000L,
            generateSessionId = { "sess-${idSeq++}" },
        )
        var idSeq = 0
        lateinit var manager: AttriaxSessionLifecycleManager
    }

    private fun fixture(): Fixture {
        val f = Fixture()
        f.manager = AttriaxSessionLifecycleManager(
            sessionManager = f.sessionManager,
            clock = f.clock,
            scheduler = f.scheduler,
            isEnabled = { f.enabled },
            currentIdentity = { identity() },
            enqueueLifecycle = { f.enqueued.add(it) },
            requestFlush = { f.flushes++ },
        )
        return f
    }

    private fun kinds(f: Fixture) = f.enqueued.map { it.kind }

    // ---- S3: initial start + heartbeat timer ----

    @Test
    fun activateEmitsSeededStartAndSchedulesHeartbeat() {
        val f = fixture()
        val started = f.sessionManager.restoreOrStart(identity())
        f.manager.seedInitialSessionStart(started.currentSession)

        f.manager.activate()

        assertEquals(listOf("start"), kinds(f))
        assertEquals(heartbeat, f.scheduler.scheduledIntervalMs)
        assertTrue(f.scheduler.hasActiveTimer)
    }

    @Test
    fun heartbeatTickEnqueuesHeartbeatAndBumpsActivity() {
        val f = fixture()
        val started = f.sessionManager.restoreOrStart(identity())
        f.manager.seedInitialSessionStart(started.currentSession)
        f.manager.activate()
        f.enqueued.clear()

        f.clock.value = 1_000L + heartbeat
        f.scheduler.tick()

        assertEquals(listOf("heartbeat"), kinds(f))
        assertEquals(f.clock.value, f.sessionManager.currentSession?.lastActivityAtMs)
    }

    // ---- S3: background pause + heartbeat cancel ----

    @Test
    fun backgroundEnqueuesPauseAndCancelsHeartbeat() {
        val f = fixture()
        f.sessionManager.restoreOrStart(identity())
        f.manager.activate()
        f.enqueued.clear()

        f.clock.value = 2_000L
        f.manager.handleBackground()

        assertEquals(listOf("pause"), kinds(f))
        assertFalse(f.scheduler.hasActiveTimer)
        assertTrue(f.scheduler.cancelCount >= 1)
    }

    @Test
    fun secondBackgroundIsNoOp() {
        val f = fixture()
        f.sessionManager.restoreOrStart(identity())
        f.manager.activate()
        f.manager.handleBackground()
        f.enqueued.clear()

        f.manager.handleBackground()

        assertTrue(f.enqueued.isEmpty())
    }

    // ---- S3: detach end ----

    @Test
    fun detachEnqueuesEndAndClearsSession() {
        val f = fixture()
        f.sessionManager.restoreOrStart(identity())
        f.manager.activate()
        f.enqueued.clear()

        f.clock.value = 3_000L
        f.manager.handleDetached()

        assertEquals(listOf("end"), kinds(f))
        assertNull(f.sessionManager.currentSession)
        assertFalse(f.scheduler.hasActiveTimer)
    }

    // ---- S2/S3: resume within window vs new session past window ----

    @Test
    fun foregroundWithinWindowEmitsResumeSameId() {
        val f = fixture()
        val started = f.sessionManager.restoreOrStart(identity())
        f.manager.activate()
        f.manager.handleBackground(2_000L)
        f.enqueued.clear()

        f.manager.handleForeground(2_000L + 5L * 60_000)

        assertEquals(listOf("resume"), kinds(f))
        assertEquals(started.currentSession.sessionId, f.sessionManager.currentSession?.sessionId)
        assertTrue(f.scheduler.hasActiveTimer)
    }

    @Test
    fun foregroundPastWindowEmitsRecoveredEndThenStart() {
        val f = fixture()
        val started = f.sessionManager.restoreOrStart(identity())
        val staleId = started.currentSession.sessionId
        f.manager.activate()
        f.manager.handleBackground(2_000L)
        f.enqueued.clear()

        f.manager.handleForeground(2_000L + 20L * 60_000)

        assertEquals(listOf("end", "start"), kinds(f))
        // The recovered end carries the stale session + recovered metadata (row S5).
        val end = f.enqueued.first { it.kind == "end" }
        assertEquals(staleId, end.session.sessionId)
        assertEquals(true, end.metadata?.get("recovered"))
        // The start is a fresh session id.
        val start = f.enqueued.first { it.kind == "start" }
        assertTrue(start.session.sessionId != staleId)
    }

    // ---- S4: keep-alive heartbeat build + successful foreground flush ----

    @Test
    fun buildKeepAliveHeartbeatReturnsCurrentSessionHeartbeat() {
        val f = fixture()
        val started = f.sessionManager.restoreOrStart(identity())
        f.manager.activate()

        val keepAlive = f.manager.buildKeepAliveHeartbeat(7_000L)

        assertEquals("heartbeat", keepAlive?.kind)
        assertEquals(started.currentSession.sessionId, keepAlive?.session?.sessionId)
        assertEquals(7_000L, keepAlive?.occurredAtMs)
    }

    @Test
    fun buildKeepAliveHeartbeatIsNullInBackground() {
        val f = fixture()
        f.sessionManager.restoreOrStart(identity())
        f.manager.activate()
        f.manager.handleBackground()

        assertNull(f.manager.buildKeepAliveHeartbeat(7_000L))
    }

    @Test
    fun successfulForegroundFlushBumpsActivityForMatchingSession() {
        val f = fixture()
        val started = f.sessionManager.restoreOrStart(identity())
        f.manager.activate()

        f.manager.handleSuccessfulForegroundFlush(started.currentSession.sessionId, 8_500L)

        assertEquals(8_500L, f.sessionManager.currentSession?.lastActivityAtMs)
    }

    @Test
    fun successfulForegroundFlushIgnoresMismatchedSession() {
        val f = fixture()
        f.sessionManager.restoreOrStart(identity())
        f.manager.activate()
        val before = f.sessionManager.currentSession?.lastActivityAtMs

        f.manager.handleSuccessfulForegroundFlush("some-other-session", 8_500L)

        assertEquals(before, f.sessionManager.currentSession?.lastActivityAtMs)
    }

    // ---- gating ----

    @Test
    fun heartbeatTickIsNoOpWhenDisabled() {
        val f = fixture()
        f.sessionManager.restoreOrStart(identity())
        f.manager.activate()
        f.enqueued.clear()

        f.enabled = false
        f.scheduler.tick()

        assertTrue(f.enqueued.isEmpty())
    }
}
