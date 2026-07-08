package com.attriax.sdk.internal.consent

import com.attriax.sdk.AttriaxConfig
import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxExecutor
import com.attriax.sdk.internal.AttriaxIso8601
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Local GDPR consent state machine + generation-guarded background sync
 * (PARITY §5, rows C1–C3). Framework-free: it depends only on the [AttriaxConfig]
 * value, an [AttriaxClock], a [AttriaxConsentStore] over the KeyValueStore port,
 * an [AttriaxConsentTransport] port, and a plain [AttriaxExecutor] for the async
 * sync — so the downgrade race can be reproduced deterministically in tests.
 *
 * Mirrors the Flutter reference `AttriaxConsentManager`.
 *
 * THE GENERATION GUARD (row C3 — the critical fix). Every local consent decision
 * ([setConsent]/[setNotRequired]/[reset]) applies immediately, then bumps the
 * monotonic [generation] counter and kicks a background sync. The sync loop
 * captures the generation BEFORE the network await; when the echo returns it
 * checks `generation != capturedGeneration`. On mismatch a NEWER local decision
 * landed mid-flight, so the (now stale) echo is DISCARDED and the loop re-syncs
 * the current state — a newer setConsent(false) can never be clobbered by an
 * in-flight older setConsent(true) echo.
 */
class AttriaxConsentManager(
    private val config: AttriaxConfig,
    private val clock: AttriaxClock,
    private val consentStore: AttriaxConsentStore,
    private val transport: AttriaxConsentTransport,
    private val syncExecutor: AttriaxExecutor,
) {
    /** Notified when the consent DECISION (state or values) changes. */
    var onStateChanged: (() -> Unit)? = null

    private val lock = SynchronizedObject()

    private var state: AttriaxGdprConsentState = AttriaxGdprConsentState.UNKNOWN
    private var values: AttriaxGdprConsentValues? = null
    private var countryCode: String? = null
    private var regionSource: String? = null
    private var checkedAtIso: String? = null
    private var pendingSync: Boolean = false
    private var restored: Boolean = false

    @Volatile private var anonymousTracking: Boolean = config.anonymousTracking

    /**
     * Monotonic generation counter (row C3). Read under [lock]; bumped on every
     * local consent decision. The sync loop compares against a value captured
     * before its network await to detect a newer decision landing mid-flight.
     */
    private var generation: Int = 0

    /** Single-flight guard so a second decision coalesces into the running sync. */
    private val syncing = atomic(false)

    // -------- read view --------

    val gdprConsentState: AttriaxGdprConsentState get() = synchronized(lock) { state }
    val gdprConsentValues: AttriaxGdprConsentValues? get() = synchronized(lock) { values }
    var anonymousTrackingEnabled: Boolean
        get() = anonymousTracking
        set(value) {
            if (anonymousTracking == value) return
            anonymousTracking = value
            onStateChanged?.invoke()
        }

    val isWaitingForGdprConsent: Boolean get() = policy().isWaitingForGdprConsent
    val shouldDeferNetworkDispatch: Boolean get() = policy().shouldDeferNetworkDispatch

    fun allowsAnalyticsTracking(): Boolean = policy().allowsCategory { it.analytics }
    fun allowsAttributionTracking(): Boolean = policy().allowsCategory { it.attribution }
    fun allowsAdEventsTracking(): Boolean = policy().allowsCategory { it.adEvents }

    fun canCaptureSignal(signal: AttriaxTrackingSignal): Boolean =
        policy().canCaptureSignal(signal)

    fun trackingDecisionFor(signal: AttriaxTrackingSignal): AttriaxTrackingDecision =
        policy().trackingDecisionFor(signal)

    /** Snapshot the policy over the current state (taken under [lock]). */
    fun policy(): AttriaxConsentPolicy = synchronized(lock) {
        AttriaxConsentPolicy(
            gdprEnabled = config.gdprEnabled,
            state = state,
            values = values,
            anonymousTrackingEnabled = anonymousTracking,
        )
    }

    // -------- lifecycle --------

    /** Restore persisted consent state (idempotent). */
    fun restore() {
        synchronized(lock) {
            if (restored) return
            val stored = consentStore.read()
            if (stored != null) {
                state = stored.state
                values = stored.values
                countryCode = stored.countryCode
                regionSource = stored.regionSource
                checkedAtIso = stored.checkedAtIso
                pendingSync = stored.pendingSync
            }
            restored = true
        }
    }

    // -------- decisions (apply locally immediately; row C2) --------

    fun setConsent(analytics: Boolean, attribution: Boolean, adEvents: Boolean) {
        applyLocalDecision(
            newState = AttriaxGdprConsentState.GRANTED,
            newValues = AttriaxGdprConsentValues(analytics, attribution, adEvents),
            newRegionSource = "manual",
        )
    }

    fun setNotRequired() {
        applyLocalDecision(
            newState = AttriaxGdprConsentState.NOT_REQUIRED,
            newValues = null,
            newRegionSource = "manual",
        )
    }

    fun reset() {
        applyLocalDecision(
            newState = AttriaxGdprConsentState.UNKNOWN,
            newValues = null,
            newRegionSource = null,
            newCountryCode = null,
        )
    }

    /**
     * needsConsent (row C1 semantics). With [localOnly] we answer from stored state
     * only; otherwise we may ask the backend (generation-guarded like the sync).
     * Returns whether the SDK is still waiting for an explicit decision.
     */
    fun needsConsent(localOnly: Boolean): Boolean {
        restore()
        val (snapshotState, capturedGeneration) = synchronized(lock) { state to generation }

        val cacheable = snapshotState == AttriaxGdprConsentState.GRANTED ||
            snapshotState == AttriaxGdprConsentState.NOT_REQUIRED
        if (localOnly || cacheable) {
            return isWaitingForGdprConsent
        }

        return try {
            val consentId = consentStore.ensureConsentId()
            val status = transport.checkGdprConsent(config.normalizedProjectToken, consentId)
            synchronized(lock) {
                // Capture-before-await guard: only apply the echo if no newer local
                // decision landed during the check.
                if (generation == capturedGeneration) {
                    applyRemoteStatusLocked(status, pending = false)
                }
            }
            isWaitingForGdprConsent
        } catch (e: Exception) {
            isWaitingForGdprConsent
        }
    }

    /** Kick a background flush of any pending sync (no-op when nothing pending). */
    fun flushPendingSync() {
        restore()
        val pending = synchronized(lock) { pendingSync }
        if (pending) scheduleSync()
    }

    fun clearMemory() {
        synchronized(lock) {
            state = AttriaxGdprConsentState.UNKNOWN
            values = null
            countryCode = null
            regionSource = null
            checkedAtIso = null
            pendingSync = false
            restored = false
            generation++
        }
    }

    // -------- internals --------

    private fun applyLocalDecision(
        newState: AttriaxGdprConsentState,
        newValues: AttriaxGdprConsentValues?,
        newRegionSource: String?,
        newCountryCode: String? = synchronized(lock) { countryCode },
    ) {
        restore()
        val decisionChanged = synchronized(lock) {
            val changed = newState != state || newValues != values
            state = newState
            values = newValues
            regionSource = newRegionSource
            countryCode = newCountryCode
            checkedAtIso = nowIso()
            pendingSync = true
            // Bump the generation for EVERY decision (row C3) so an in-flight echo
            // for the previous decision is detected as stale even when the state
            // token is unchanged (e.g. granted→granted with different values).
            generation++
            persistCurrentStateLocked()
            changed
        }
        if (decisionChanged) onStateChanged?.invoke()
        scheduleSync()
    }

    private fun scheduleSync() {
        // Single-flight: only one sync task runs; a second decision that lands
        // before the task starts sets pendingSync (already true) and the running
        // loop re-reads the current state, so it converges without a second task.
        if (!syncing.compareAndSet(false, true)) return
        syncExecutor.execute {
            try {
                runSyncLoop()
            } finally {
                syncing.value = false
            }
            // A decision may have landed (and found syncing==true, so did NOT
            // schedule) between the loop's last generation read and the flag
            // release; re-schedule so its intent is not stranded.
            val stillPending = synchronized(lock) { pendingSync }
            if (stillPending) scheduleSync()
        }
    }

    /**
     * The generation-guarded convergence loop (row C3). Each iteration captures the
     * generation BEFORE the upsert await; if it advanced by the time the echo
     * returns, a newer local decision landed and the echo is DISCARDED (we loop and
     * re-sync the now-current state). Otherwise the echo is applied and we stop.
     */
    private fun runSyncLoop() {
        while (true) {
            val snapshot = synchronized(lock) {
                if (!pendingSync) return
                SyncSnapshot(
                    generation = generation,
                    state = state,
                    values = values,
                    countryCode = countryCode,
                    regionSource = regionSource,
                    checkedAtIso = checkedAtIso,
                )
            }

            val status = try {
                val consentId = consentStore.ensureConsentId()
                transport.upsertGdprConsent(
                    projectToken = config.normalizedProjectToken,
                    consentId = consentId,
                    state = snapshot.state,
                    values = snapshot.values,
                    countryCode = snapshot.countryCode,
                    regionSource = snapshot.regionSource,
                    clientOccurredAtIso = snapshot.checkedAtIso,
                )
            } catch (e: Exception) {
                // Transient failure: leave pendingSync set so a later flush retries.
                synchronized(lock) {
                    pendingSync = true
                    persistCurrentStateLocked()
                }
                return
            }

            val applied = synchronized(lock) {
                if (generation != snapshot.generation) {
                    // A newer local decision landed while we awaited this upsert.
                    // The echo reflects the OLD intent; discard it and re-sync.
                    false
                } else {
                    applyRemoteStatusLocked(status, pending = false)
                    true
                }
            }
            if (applied) return
            // else: loop again with the now-current state (stale echo discarded).
        }
    }

    private data class SyncSnapshot(
        val generation: Int,
        val state: AttriaxGdprConsentState,
        val values: AttriaxGdprConsentValues?,
        val countryCode: String?,
        val regionSource: String?,
        val checkedAtIso: String?,
    )

    /** Apply a remote echo. Must hold [lock]. Does NOT bump the generation. */
    private fun applyRemoteStatusLocked(
        status: AttriaxRemoteConsentStatus,
        pending: Boolean,
    ) {
        var mappedState = status.state
        val mappedValues = status.values
        if (mappedState == AttriaxGdprConsentState.GRANTED && mappedValues == null) {
            mappedState = AttriaxGdprConsentState.PENDING
        }
        val decisionChanged = mappedState != state || mappedValues != values
        state = mappedState
        values = mappedValues
        checkedAtIso = status.checkedAtIso ?: checkedAtIso
        normalize(status.countryCode)?.let { countryCode = it }
        normalize(status.regionSource)?.let { regionSource = it }
        pendingSync = pending
        persistCurrentStateLocked()
        if (decisionChanged) {
            // Notify outside the lock is preferable, but the listener is a light
            // signal in practice; keep it simple and consistent with Flutter.
            onStateChanged?.invoke()
        }
    }

    /** Persist the current state. Must hold [lock]. */
    private fun persistCurrentStateLocked() {
        if (!pendingSync && state == AttriaxGdprConsentState.UNKNOWN) {
            consentStore.write(null)
            return
        }
        consentStore.write(
            AttriaxStoredConsent(
                state = state,
                values = values,
                countryCode = countryCode,
                regionSource = regionSource,
                checkedAtIso = checkedAtIso,
                pendingSync = pendingSync,
            ),
        )
    }

    private fun normalize(value: String?): String? {
        val trimmed = value?.trim()
        return if (trimmed.isNullOrEmpty()) null else trimmed
    }

    private fun nowIso(): String = AttriaxIso8601.formatUtcMillis(clock.nowMs())
}
