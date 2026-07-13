package com.attriax.sdk.internal.session

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxScheduler
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * A lifecycle telemetry request to enqueue: a session snapshot + kind + the
 * timestamp the event occurred at + optional metadata (e.g. `{recovered:true}`).
 * The engine turns this into an [com.attriax.sdk.internal.request.AttriaxApiRequest]
 * and pushes it through the consent-gated queue.
 */
data class AttriaxSessionLifecycleEvent(
    val session: AttriaxSessionSnapshot,
    val kind: String,
    val occurredAtMs: Long,
    val metadata: Map<String, Any?>? = null,
)

/**
 * Pure session lifecycle + heartbeat state machine.
 *
 * Owns the foreground/background/detach transitions, the heartbeat timer, the
 * pending initial-start and recovered-end telemetry, and the successful-flush
 * activity bump. Framework-free: foreground/background signals arrive via
 * [handleForeground]/[handleBackground]/[handleDetached] (the Android layer wires
 * ProcessLifecycleOwner to these), the timer runs through the injected
 * [AttriaxScheduler] seam (deterministic in tests), and enqueue is delegated to
 * [enqueueLifecycle]. Mirrors Flutter `AttriaxSessionLifecycleManager`.
 *
 *  - foreground after the continuation window → START a new session (+ recovered
 *    END for the replaced one); within the window → RESUME the same id.
 *  - background/hidden → PAUSE + stop the heartbeat.
 *  - process detach → END.
 *  - a heartbeat timer at `session.heartbeatInterval` enqueues a HEARTBEAT.
 *
 * All enqueue paths are gated on `isEnabled() && sessionManager.isTrackingEnabled`
 * and are no-ops in the background (except the terminal pause/end transitions).
 */
class AttriaxSessionLifecycleManager(
    private val sessionManager: AttriaxSessionManager,
    private val clock: AttriaxClock,
    private val scheduler: AttriaxScheduler,
    private val isEnabled: () -> Boolean,
    /** Whether identified vs anonymous session capture is currently allowed. */
    private val currentIdentity: () -> AttriaxSessionIdentity,
    /** Enqueue a built lifecycle event through the engine's consent-gated queue. */
    private val enqueueLifecycle: (AttriaxSessionLifecycleEvent) -> Unit,
    /** Kick a best-effort flush after a batch of lifecycle events is enqueued. */
    private val requestFlush: () -> Unit,
) {
    private val lock = SynchronizedObject()

    private var heartbeatHandle: AttriaxScheduler.ScheduledHandle? = null
    private var isInBackground: Boolean = false
    private var isActive: Boolean = false
    private var pendingInitialStart: AttriaxSessionSnapshot? = null
    private var pendingRecoveredEnd: AttriaxSessionSnapshot? = null

    val inBackground: Boolean get() = synchronized(lock) { isInBackground }

    /** Seed the START to emit for a freshly-started restore session. */
    fun seedInitialSessionStart(session: AttriaxSessionSnapshot?) = synchronized(lock) {
        pendingInitialStart = session
    }

    /** Seed the recovered END to emit for a replaced restore session. */
    fun seedRecoveredSessionEnd(session: AttriaxSessionSnapshot?) = synchronized(lock) {
        pendingRecoveredEnd = session
    }

    /**
     * Activate telemetry (called once the runtime is foregrounded / init completes):
     * flush any pending initial START and (re)start the heartbeat timer.
     */
    fun activate() {
        val toFlush = synchronized(lock) {
            isActive = true
            val pending = flushPendingInitialStartLocked()
            restartHeartbeatLocked()
            pending
        }
        toFlush?.invoke()
    }

    /** Stop the heartbeat and mark inactive (deactivate / dispose). */
    fun deactivate() = synchronized(lock) {
        isActive = false
        stopHeartbeatLocked()
    }

    fun reset() = synchronized(lock) {
        stopHeartbeatLocked()
        isActive = false
        isInBackground = false
        pendingInitialStart = null
        pendingRecoveredEnd = null
    }

    /**
     * The app moved to the foreground. On the first foreground of a launch
     * (was not in background) this is a no-op beyond (re)starting the heartbeat —
     * the initial START was already seeded at restore. A foreground FROM background
     * resumes the same session (within window) or starts a new one (past window,
     * with a recovered END for the replaced session).
     */
    fun handleForeground(atMs: Long = clock.nowMs()) {
        val actions = ArrayList<() -> Unit>()
        synchronized(lock) {
            val wasInBackground = isInBackground
            isInBackground = false
            if (!isActive || !isEnabled() || !sessionManager.isTrackingEnabled) {
                restartHeartbeatLocked()
                return@synchronized
            }
            if (!wasInBackground) {
                restartHeartbeatLocked()
                return@synchronized
            }

            val result = sessionManager.resumeOrStart(currentIdentity(), atMs)
            result.replacedSession?.let { replaced ->
                actions += enqueueRecoveredEndAction(replaced)
            }
            val kind = if (result.startedNewSession) {
                AttriaxSessionContinuation.Lifecycle.START
            } else {
                AttriaxSessionContinuation.Lifecycle.RESUME
            }
            val occurredAt = if (result.startedNewSession) result.currentSession.startedAtMs else atMs
            actions += enqueueLifecycleAction(kind, result.currentSession, occurredAt)
            restartHeartbeatLocked()
        }
        runActions(actions)
    }

    /**
     * The app moved to the background/hidden: PAUSE the current session and
     * stop the heartbeat. A no-op if already backgrounded.
     */
    fun handleBackground(atMs: Long = clock.nowMs()) {
        val actions = ArrayList<() -> Unit>()
        synchronized(lock) {
            val wasInBackground = isInBackground
            isInBackground = true
            stopHeartbeatLocked()
            if (wasInBackground || !isEnabled() || !sessionManager.isTrackingEnabled) {
                return@synchronized
            }
            val session = sessionManager.recordActivity(atMs) ?: return@synchronized
            flushPendingRecoveredEndLocked()?.let { actions += it }
            actions += enqueueLifecycleAction(
                AttriaxSessionContinuation.Lifecycle.PAUSE, session, atMs,
            )
        }
        runActions(actions, flushAfter = true)
    }

    /**
     * The process is detaching: END the current session and stop the
     * heartbeat.
     */
    fun handleDetached(atMs: Long = clock.nowMs()) {
        val actions = ArrayList<() -> Unit>()
        synchronized(lock) {
            isInBackground = true
            stopHeartbeatLocked()
            if (!isEnabled() || !sessionManager.isTrackingEnabled) return@synchronized
            val session = sessionManager.end(atMs) ?: return@synchronized
            actions += enqueueLifecycleAction(
                AttriaxSessionContinuation.Lifecycle.END, session, atMs,
            )
        }
        runActions(actions, flushAfter = true)
    }

    /**
     * Called by the dispatcher when a batch carrying an event tagged with
     * [sessionId] is delivered (keep-alive). Bumps the session's
     * last-activity to [occurredAtMs] and restarts the heartbeat, so a stream of
     * foreground events keeps the session alive without emitting extra heartbeats.
     * Mirrors Flutter `handleSuccessfulForegroundFlush`.
     */
    fun handleSuccessfulForegroundFlush(sessionId: String, occurredAtMs: Long) = synchronized(lock) {
        if (!isEnabled() || !sessionManager.isTrackingEnabled || isInBackground) return
        val session = sessionManager.currentSession ?: return
        if (session.sessionId != sessionId) return
        sessionManager.recordActivity(occurredAtMs)
        restartHeartbeatLocked()
    }

    /**
     * Build the keep-alive HEARTBEAT lifecycle event for the current session at
     * [occurredAtMs]. Returns null when there is no active foreground
     * session — the dispatcher then appends no synthetic keep-alive.
     */
    fun buildKeepAliveHeartbeat(occurredAtMs: Long = clock.nowMs()): AttriaxSessionLifecycleEvent? =
        synchronized(lock) {
            if (isInBackground) return null
            val session = sessionManager.currentSession ?: return null
            AttriaxSessionLifecycleEvent(
                session = session,
                kind = AttriaxSessionContinuation.Lifecycle.HEARTBEAT,
                occurredAtMs = occurredAtMs,
            )
        }

    // -------- internals --------

    /** Timer tick: record activity + enqueue a HEARTBEAT. */
    private fun onHeartbeatTick() {
        val actions = ArrayList<() -> Unit>()
        synchronized(lock) {
            if (!isEnabled() || !sessionManager.isTrackingEnabled) return@synchronized
            val occurredAt = clock.nowMs()
            val session = sessionManager.recordActivity(occurredAt) ?: return@synchronized
            flushPendingRecoveredEndLocked()?.let { actions += it }
            actions += enqueueLifecycleAction(
                AttriaxSessionContinuation.Lifecycle.HEARTBEAT, session, occurredAt,
            )
        }
        runActions(actions)
    }

    private fun restartHeartbeatLocked() {
        stopHeartbeatLocked()
        val session = sessionManager.currentSession
        if (!isActive || !isEnabled() || !sessionManager.isTrackingEnabled ||
            isInBackground || session == null
        ) {
            return
        }
        heartbeatHandle = scheduler.schedulePeriodic(session.heartbeatIntervalMs) { onHeartbeatTick() }
    }

    private fun stopHeartbeatLocked() {
        heartbeatHandle?.cancel()
        heartbeatHandle = null
    }

    /** Consume the pending initial START, returning the enqueue action (or null). */
    private fun flushPendingInitialStartLocked(): (() -> Unit)? {
        val session = pendingInitialStart
        if (session == null || !isEnabled() || !sessionManager.isTrackingEnabled || isInBackground) {
            return null
        }
        pendingInitialStart = null
        return enqueueLifecycleAction(
            AttriaxSessionContinuation.Lifecycle.START, session, session.startedAtMs,
        )
    }

    /** Consume the pending recovered END, returning the enqueue action (or null). */
    private fun flushPendingRecoveredEndLocked(): (() -> Unit)? {
        val session = pendingRecoveredEnd
        if (session == null || !isEnabled() || !sessionManager.isTrackingEnabled) return null
        pendingRecoveredEnd = null
        return enqueueRecoveredEndAction(session)
    }

    private fun enqueueRecoveredEndAction(session: AttriaxSessionSnapshot): () -> Unit {
        val occurredAt = sessionManager.inferredRecoveredEndAtMs(session)
        return {
            enqueueLifecycle(
                AttriaxSessionLifecycleEvent(
                    session = session,
                    kind = AttriaxSessionContinuation.Lifecycle.END,
                    occurredAtMs = occurredAt,
                    metadata = mapOf("recovered" to true),
                ),
            )
        }
    }

    private fun enqueueLifecycleAction(
        kind: String,
        session: AttriaxSessionSnapshot,
        occurredAtMs: Long,
    ): () -> Unit = {
        enqueueLifecycle(AttriaxSessionLifecycleEvent(session, kind, occurredAtMs))
    }

    /**
     * Run the collected enqueue actions OUTSIDE the lock (they call back into the
     * engine's queue/consent path, which must not run under the session lock), then
     * optionally kick a flush.
     */
    private fun runActions(actions: List<() -> Unit>, flushAfter: Boolean = false) {
        actions.forEach { it() }
        if (flushAfter || actions.isNotEmpty()) requestFlush()
    }
}
