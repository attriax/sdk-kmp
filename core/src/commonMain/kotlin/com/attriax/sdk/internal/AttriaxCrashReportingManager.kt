package com.attriax.sdk.internal

import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.request.AttriaxApiRequest
import com.attriax.sdk.internal.request.AttriaxEndpoints

/**
 * Automatic crash reporting (Flutter reference
 * `AttriaxCrashReportingManager`, attriax_crash_reporting_manager.dart).
 *
 * Two durability strategies, both persisting the crash as the SAME crash/error wire
 * request the manual [com.attriax.sdk.AttriaxTracking.recordError] builds (reused via
 * [buildFatalCrash] / the engine crash builder — no new DTO):
 *
 * Fatal crashes are always **persist-only** — never enqueued at capture time. Whether
 * the crash arrives via the OS uncaught handler or a wrapper's `recordError(fatal =
 * true)`, the record is written to the [KeyValueStore] SYNCHRONOUSLY and delivered
 * EXCLUSIVELY on the NEXT [com.attriax.sdk.Attriax.init] via [replayPendingCrashReport],
 * which enqueues it through the durable queue and clears the record (one-shot). This
 * gives exactly-once delivery — the durable queue persists + retries across restarts,
 * so a single enqueue on replay is loss-free without ever double-sending the happy path.
 * Mirrors Flutter `_persistFatalCrashForRetry` (persist only) + `_replayPendingCrashReport`.
 *
 * Only NON-fatal `recordError(fatal = false)` enqueues immediately (no persist) — it is
 * a normal buffered request, not a process-death backup.
 *
 * The persist key + format mirror Flutter's `pendingCrashReportStorageKey`
 * ('attriax.crash.pending') — the crash request body JSON. The whole surface is
 * gated by [enabled] (`AttriaxConfig.automaticCrashReportingEnabled`, default `true`
 * matching Flutter): when off, no handler is installed and no replay happens.
 *
 * All I/O goes straight through the injected [store] — never the background executor
 * — because the synchronous persist runs while the process is terminating.
 */
internal class AttriaxCrashReportingManager(
    private val enabled: Boolean,
    private val store: KeyValueStore,
    /** Enqueue a crash through the engine's normal (consent-gated) queue path. */
    private val enqueueCrash: (AttriaxApiRequest) -> Unit,
    /** Build a FATAL crash request for a captured [Throwable] (source `uncaught_exception`). */
    private val buildFatalCrash: (Throwable) -> AttriaxApiRequest,
    /** Platform seam that installs the OS uncaught-exception handler. */
    private val installUncaughtHandler:
        (onFatalCrash: (Throwable) -> Unit) -> AttriaxUncaughtHandlerRegistration,
    private val logError: (String) -> Unit,
) {
    private var registration: AttriaxUncaughtHandlerRegistration? = null

    /**
     * Install the OS uncaught-exception handler (idempotent). No-op when crash
     * reporting is disabled or on the native placeholder (returns [Noop]).
     */
    fun install() {
        if (!enabled) return
        if (registration != null) return
        registration = installUncaughtHandler { throwable -> onFatalCrash(throwable) }
    }

    /** Restore the previous handler (idempotent); called on dispose/reset. */
    fun uninstall() {
        registration?.uninstall()
        registration = null
    }

    /**
     * One-shot replay of a crash a prior fatal handler persisted (
     * `_replayPendingCrashReport`). Enqueues it through the normal queue path, then
     * clears the record regardless of outcome so a poison payload never loops across
     * restarts. Gated by [enabled] — a disabled runtime neither replays nor clears.
     */
    fun replayPendingCrashReport() {
        if (!enabled) return
        val raw = store.getString(KEY_PENDING_CRASH)
        if (raw.isNullOrEmpty()) return
        try {
            @Suppress("UNCHECKED_CAST")
            val body = Json.decode(raw) as? Map<String, Any?>
            if (body != null) {
                enqueueCrash(
                    AttriaxApiRequest(AttriaxApiRequest.KIND_TRACK_CRASH, AttriaxEndpoints.CRASHES, body),
                )
            }
        } catch (e: Exception) {
            logError("Attriax: discarding unreadable pending crash report (${e.message}).")
        } finally {
            store.remove(KEY_PENDING_CRASH)
        }
    }

    /**
     * Public wrapper-callable fatal path: PERSIST ONLY (mirrors Flutter
     * `_persistFatalCrashForRetry`). The crash is intentionally NOT enqueued here — a
     * fatal report is delivered exclusively via [replayPendingCrashReport] on the next
     * init, which enqueues it once into the durable queue and clears the record. Enqueuing
     * here too would double-send any fatal crash whose immediate copy actually delivered.
     */
    fun reportFatal(request: AttriaxApiRequest) {
        persist(request)
    }

    /**
     * OS uncaught-exception callback. SYNCHRONOUS persist ONLY — the process is
     * dying, so we cannot rely on the async queue/flush; recovery happens via
     * [replayPendingCrashReport] on the next launch (`_persistFatalCrashForRetry`).
     */
    private fun onFatalCrash(throwable: Throwable) {
        try {
            persist(buildFatalCrash(throwable))
        } catch (e: Throwable) {
            // Never mask the original crash with a reporting failure.
        }
    }

    /** Synchronous single-key persist (the crash request body JSON). */
    private fun persist(request: AttriaxApiRequest) {
        store.putString(KEY_PENDING_CRASH, Json.encode(request.body))
    }

    companion object {
        /** Flutter `pendingCrashReportStorageKey`. */
        const val KEY_PENDING_CRASH = "attriax.crash.pending"

        /** Source label stamped on OS-captured fatal crashes. */
        const val SOURCE_UNCAUGHT_EXCEPTION = "uncaught_exception"
    }
}
