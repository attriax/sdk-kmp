package com.attriax.sdk.internal.session

/**
 * A persisted session snapshot's identity + timing fields relevant to the
 * continuation decision (PARITY §3, row S2/S5).
 */
data class AttriaxSessionSnapshot(
    val sessionId: String,
    val startedAtMs: Long,
    val lastActivityAtMs: Long,
    val heartbeatIntervalMs: Long,
    val deviceId: String?,
    val platform: String,
    val appPackageName: String?,
    val appVersion: String?,
    val appBuildNumber: String?,
    // Context carried on the session lifecycle wire payload (SdkSessionDto).
    val locale: String? = null,
    val isFirstLaunch: Boolean = false,
    val sdkPackageVersion: String? = null,
) {
    /** Clamped ms-since-start for a lifecycle event at [occurredAtMs] (row S3). */
    fun sessionRelativeTimeMs(occurredAtMs: Long): Long =
        (occurredAtMs - startedAtMs).coerceIn(0L, Int.MAX_VALUE.toLong())
}

/** Identity + timing of the current launch, compared against a restored snapshot. */
data class AttriaxSessionContext(
    val deviceId: String?,
    val platform: String,
    val appPackageName: String?,
    val appVersion: String?,
    val appBuildNumber: String?,
)

/**
 * Session continuation-window policy (PARITY §3, row S2;
 * Flutter `session/attriax_session_continuation_policy.dart`).
 *
 * Window = `2 × heartbeatInterval` clamped to `[60s, 30min]`. On restore, a
 * session continues (same id, bumped activity) only when the device/platform/app
 * identity match and its age since last activity is within the window.
 */
object AttriaxSessionContinuation {
    const val MIN_WINDOW_MS = 60_000L        // 60s lower bound
    const val MAX_WINDOW_MS = 30L * 60_000L  // 30min upper bound

    /** Clamped continuation window for a snapshot's heartbeat interval. */
    fun continuationWindowMs(heartbeatIntervalMs: Long): Long {
        val raw = heartbeatIntervalMs * 2
        return when {
            raw < MIN_WINDOW_MS -> MIN_WINDOW_MS
            raw > MAX_WINDOW_MS -> MAX_WINDOW_MS
            else -> raw
        }
    }

    /**
     * Whether [snapshot] should be continued given the current-launch [context]
     * and wall-clock [nowMs]. Returns false (→ start new + queue recovered-end)
     * when the snapshot is absent, identity drifted, its start is in the future,
     * or it is older than the continuation window.
     */
    fun shouldContinue(
        snapshot: AttriaxSessionSnapshot?,
        context: AttriaxSessionContext,
        nowMs: Long,
    ): Boolean {
        if (snapshot == null) return false
        if (snapshot.deviceId != context.deviceId) return false
        if (snapshot.platform != context.platform) return false
        if (snapshot.appPackageName != context.appPackageName) return false
        if (snapshot.appVersion != context.appVersion) return false
        if (snapshot.appBuildNumber != context.appBuildNumber) return false
        if (snapshot.startedAtMs > nowMs) return false

        val age = nowMs - snapshot.lastActivityAtMs
        return age <= continuationWindowMs(snapshot.heartbeatIntervalMs)
    }

    /**
     * Inferred `end` timestamp for a recovered (replaced) session (row S5). The
     * session ended while the app was not running, so its projected end
     * (lastActivity + window) is clamped to [nowMs] — it can never postdate the
     * replacing session's start, which would produce out-of-order lifecycle events.
     * Mirrors Flutter `AttriaxSessionManager.inferredEndAt`.
     */
    fun inferredRecoveredEndAtMs(snapshot: AttriaxSessionSnapshot, nowMs: Long): Long {
        val projectedEnd = snapshot.lastActivityAtMs +
            continuationWindowMs(snapshot.heartbeatIntervalMs)
        return if (projectedEnd > nowMs) nowMs else projectedEnd
    }

    /** Session lifecycle kinds (row S3). */
    object Lifecycle {
        const val START = "start"
        const val HEARTBEAT = "heartbeat"
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val END = "end"

        val ALL = listOf(START, HEARTBEAT, PAUSE, RESUME, END)
    }
}
