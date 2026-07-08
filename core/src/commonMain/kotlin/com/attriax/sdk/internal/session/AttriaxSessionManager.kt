package com.attriax.sdk.internal.session

import com.attriax.sdk.internal.AttriaxClock
import kotlin.concurrent.Volatile

/**
 * Identity + timing of the current launch, used to build a fresh session and to
 * decide continue-vs-new against a restored snapshot (PARITY §3, rows S2/S5).
 */
data class AttriaxSessionIdentity(
    val deviceId: String?,
    val platform: String,
    val appPackageName: String?,
    val appVersion: String?,
    val appBuildNumber: String?,
    val locale: String?,
    val isFirstLaunch: Boolean,
    val sdkPackageVersion: String?,
) {
    fun toContinuationContext(): AttriaxSessionContext = AttriaxSessionContext(
        deviceId = deviceId,
        platform = platform,
        appPackageName = appPackageName,
        appVersion = appVersion,
        appBuildNumber = appBuildNumber,
    )
}

/** Outcome of a restore/resume decision (row S5): what is current, and what it replaced. */
data class AttriaxSessionRestoreResult(
    val currentSession: AttriaxSessionSnapshot,
    val startedNewSession: Boolean,
    /** The session that was replaced (→ recovered-end), or null when continued. */
    val replacedSession: AttriaxSessionSnapshot?,
)

/**
 * Pure session state machine (PARITY §3, rows S2/S3/S5). Framework-free and
 * unit-testable: it holds the current snapshot in memory, persists it through
 * [snapshotStore], and derives continue-vs-new via [AttriaxSessionContinuation].
 * The lifecycle telemetry (heartbeat timers, foreground/background transitions,
 * enqueue) lives in [AttriaxSessionLifecycleManager]; this class owns ONLY the
 * snapshot + timing. Mirrors Flutter `AttriaxSessionManager`.
 *
 * The heartbeat interval for a new session is chosen by first-launch:
 * [firstLaunchHeartbeatIntervalMs] (30s default) for the very first launch, else
 * [heartbeatIntervalMs] (5min default) — row S3.
 */
class AttriaxSessionManager(
    private val clock: AttriaxClock,
    private val snapshotStore: AttriaxSessionSnapshotStore,
    private val heartbeatIntervalMs: Long,
    private val firstLaunchHeartbeatIntervalMs: Long,
    private val generateSessionId: () -> String,
) {
    @Volatile
    var currentSession: AttriaxSessionSnapshot? = null
        private set

    @Volatile
    var isTrackingEnabled: Boolean = false
        private set

    /**
     * Restore the persisted snapshot at launch, continuing it (same id, bumped
     * activity) when identity matches and it is within the continuation window,
     * else starting a new session and reporting the replaced one (row S5).
     */
    fun restoreOrStart(identity: AttriaxSessionIdentity): AttriaxSessionRestoreResult {
        isTrackingEnabled = true
        val now = clock.nowMs()
        val stored = snapshotStore.read()
        return decide(stored, identity, now)
    }

    /**
     * Resume the in-memory session (foreground after background), continuing it
     * when within the window, else starting a new session + reporting the replaced
     * one. Distinct from [restoreOrStart] in that it compares against the live
     * in-memory session rather than the persisted snapshot.
     */
    fun resumeOrStart(identity: AttriaxSessionIdentity, atMs: Long = clock.nowMs()): AttriaxSessionRestoreResult {
        return decide(currentSession, identity, atMs)
    }

    private fun decide(
        candidate: AttriaxSessionSnapshot?,
        identity: AttriaxSessionIdentity,
        nowMs: Long,
    ): AttriaxSessionRestoreResult {
        val continued = AttriaxSessionContinuation.shouldContinue(
            candidate, identity.toContinuationContext(), nowMs,
        )
        val session = if (continued) {
            candidate!!.copy(lastActivityAtMs = nowMs)
        } else {
            buildSession(identity, nowMs)
        }
        currentSession = session
        snapshotStore.write(session)
        return AttriaxSessionRestoreResult(
            currentSession = session,
            startedNewSession = !continued,
            replacedSession = if (continued) null else candidate,
        )
    }

    /**
     * Bump the current session's last-activity to [atMs] (used by heartbeat /
     * pause / successful foreground flush). Monotonic: an out-of-order (earlier)
     * timestamp is ignored. Returns the (possibly unchanged) current session, or
     * null when there is no active session.
     */
    fun recordActivity(atMs: Long = clock.nowMs()): AttriaxSessionSnapshot? {
        val session = currentSession ?: return null
        if (atMs < session.lastActivityAtMs) return session
        val updated = session.copy(lastActivityAtMs = atMs)
        currentSession = updated
        snapshotStore.write(updated)
        return updated
    }

    /**
     * End the current session (process detach), clearing it from memory and
     * storage. Returns the final snapshot (with last-activity advanced to [atMs]
     * unless that would move it backwards), or null when none was active.
     */
    fun end(atMs: Long = clock.nowMs()): AttriaxSessionSnapshot? {
        val session = currentSession ?: return null
        val finalSession = if (atMs < session.lastActivityAtMs) {
            session
        } else {
            session.copy(lastActivityAtMs = atMs)
        }
        currentSession = null
        snapshotStore.write(null)
        return finalSession
    }

    /** Inferred recovered-end timestamp for a replaced [session] (row S5). */
    fun inferredRecoveredEndAtMs(session: AttriaxSessionSnapshot): Long =
        AttriaxSessionContinuation.inferredRecoveredEndAtMs(session, clock.nowMs())

    /** Clear the current session from memory + storage (reset / disabled). */
    fun clear() {
        currentSession = null
        snapshotStore.write(null)
    }

    /** Full reset to the disabled/no-session state (PARITY reset). */
    fun reset() {
        isTrackingEnabled = false
        clear()
    }

    private fun buildSession(identity: AttriaxSessionIdentity, nowMs: Long): AttriaxSessionSnapshot =
        AttriaxSessionSnapshot(
            sessionId = generateSessionId(),
            startedAtMs = nowMs,
            lastActivityAtMs = nowMs,
            heartbeatIntervalMs = if (identity.isFirstLaunch) {
                firstLaunchHeartbeatIntervalMs
            } else {
                heartbeatIntervalMs
            },
            deviceId = identity.deviceId,
            platform = identity.platform,
            appPackageName = identity.appPackageName,
            appVersion = identity.appVersion,
            appBuildNumber = identity.appBuildNumber,
            locale = identity.locale,
            isFirstLaunch = identity.isFirstLaunch,
            sdkPackageVersion = identity.sdkPackageVersion,
        )
}
