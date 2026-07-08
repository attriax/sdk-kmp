package com.attriax.sdk.internal.deeplink

import com.attriax.sdk.AttriaxBrowserAction
import com.attriax.sdk.AttriaxDeepLinkEvent
import com.attriax.sdk.AttriaxDeepLinkListener
import com.attriax.sdk.AttriaxDeepLinkTrigger
import com.attriax.sdk.AttriaxRawDeepLinkEvent
import com.attriax.sdk.AttriaxRawDeepLinkListener
import com.attriax.sdk.internal.AttriaxLatch
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Owns deep-link listener lifecycle, near-duplicate suppression, initial-link
 * probe state, deferred fire-once recovery, and observer fan-out (PARITY §6, rows
 * DL1–DL4). Mirrors the Flutter reference `attriax_deep_link_manager.dart` +
 * `attriax_deep_link_listener.dart`, adapted to the engine's plain-thread /
 * listener model (no coroutines — PARITY deep-links scope).
 *
 * Network dispatch is delegated to [resolveDispatch] (supplied by the engine) so
 * this coordinator stays free of transport/queue concerns and remains easy to test
 * with a fake dispatcher. The pure URI/normalization/recovery logic lives in
 * [AttriaxDeepLinkResolver] / [AttriaxDeepLinkDeferredRecovery].
 */
class AttriaxDeepLinkManager(
    private val nowMs: () -> Long,
    /**
     * Dispatch a resolve request for [uri] with the given metadata + source. The
     * engine builds the DTO, gates it through consent, and enqueues it; when the
     * backend responds it calls back with the decoded resolution `data` map (or
     * null on failure), which this manager turns into an emitted event.
     */
    private val resolveDispatch: (uri: AttriaxUri, metadata: Map<String, Any?>, source: String, isInitialLink: Boolean, onResolved: (Map<String, Any?>?) -> Unit) -> Unit,
    /** Reads the persisted "deferred deep-link already handled" flag. */
    private val readDeferredHandled: () -> Boolean,
    /** Persists the deferred-handled flag so the deferred link fires ONCE (row DL3). */
    private val writeDeferredHandled: (Boolean) -> Unit,
    /**
     * Open a resolution's browser-fallback action, returning whether the SDK handled
     * it (PARITY §6 — Flutter `_buildResolutionWithBrowserHandling` →
     * `AttriaxDeepLinkBrowserHandler.handle`). The engine supplies a function that
     * already applies the [com.attriax.sdk.AttriaxConfig.automaticBrowserHandling]
     * gate and the platform opener; the default no-ops (never handled). Invoked ONLY
     * for incoming + manual resolutions, never for deferred recovery (matching the
     * reference, whose `buildDeferredResolution` performs no browser handling).
     */
    private val handleBrowserAction: (AttriaxBrowserAction?) -> Boolean = { false },
    /** Dedup window for identical URIs (row DL2). Default 2s per the reference. */
    private val dedupWindowMs: Long = DEFAULT_DEDUP_WINDOW_MS,
) {
    private val lock = SynchronizedObject()

    // Guarded by [lock] (plain lists; every access is inside the lock, snapshotting
    // before notifying outside it — replaces the JVM CopyOnWriteArrayList).
    private val deepLinkListeners = mutableListOf<AttriaxDeepLinkListener>()
    private val rawListeners = mutableListOf<AttriaxRawDeepLinkListener>()

    private var lastHandledRaw: String? = null
    private var lastHandledAtMs: Long = 0

    /**
     * Per-raw-event resolution slots (PARITY §6 — Flutter event-hub
     * `_pendingDeepLinkResults`). Registered when a link is staged for resolution and
     * completed (with the resolved event, or null on failure/no-capture) when the
     * backend responds, so [waitResolution] can block for a specific raw event's
     * outcome. Kept after completion (like the Flutter completers) so a late waiter
     * still observes the resolved value. Guarded by [lock].
     */
    private val pendingResolutions = mutableMapOf<AttriaxRawDeepLinkEvent, ResolutionSlot>()

    @Volatile private var latestEvent: AttriaxDeepLinkEvent? = null
    @Volatile private var initialEvent: AttriaxDeepLinkEvent? = null
    @Volatile private var rawInitialEvent: AttriaxRawDeepLinkEvent? = null
    private val initialResolved = atomic(false)
    private val initialLatch = AttriaxLatch(1)
    private val deferredFiredThisRuntime = atomic(false)

    val latestDeepLink: AttriaxDeepLinkEvent? get() = latestEvent
    val initialDeepLink: AttriaxDeepLinkEvent? get() = initialEvent
    val rawInitialDeepLink: AttriaxRawDeepLinkEvent? get() = rawInitialEvent
    val isInitialDeepLinkResolved: Boolean get() = initialResolved.value

    /**
     * Register a deep-link listener. If a deep link has ALREADY been emitted (e.g.
     * a deferred link recovered from the app-open response before the host wired its
     * listener), it is replayed to the new listener immediately so a late subscriber
     * never misses the current value. This mirrors the Flutter reference's
     * `latestDeepLink`-backed broadcast semantics and removes a listener-registration
     * race for links that resolve during init.
     */
    fun addListener(listener: AttriaxDeepLinkListener) {
        // Synchronize add + replay against emit on `lock` so a concurrent emit never
        // double-delivers the same event: either the emit completes first (this
        // listener isn't registered yet → it is replayed here), or registration
        // completes first (this listener receives the live emit, and replay below
        // sees the same latestEvent already delivered → suppressed via reference id).
        synchronized(lock) {
            val alreadyDelivered = latestEvent
            deepLinkListeners.add(listener)
            alreadyDelivered?.let { listener.onDeepLink(it) }
        }
    }
    fun removeListener(listener: AttriaxDeepLinkListener) {
        synchronized(lock) { deepLinkListeners.remove(listener) }
    }
    fun addRawListener(listener: AttriaxRawDeepLinkListener) {
        synchronized(lock) { rawListeners.add(listener) }
    }
    fun removeRawListener(listener: AttriaxRawDeepLinkListener) {
        synchronized(lock) { rawListeners.remove(listener) }
    }

    /**
     * Block until the initial-link probe completes, returning the launch deep-link
     * event (or null when none was present). Returns immediately once resolved.
     * MUST be called off the main thread (it blocks).
     */
    fun waitForInitialDeepLink(timeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS): AttriaxDeepLinkEvent? {
        if (initialResolved.value) return initialEvent
        initialLatch.await(timeoutMs)
        return initialEvent
    }

    /** Mark the initial-link probe complete with no launch link present. */
    fun completeInitialLinkIfAbsent() {
        if (initialResolved.compareAndSet(false, true)) {
            initialLatch.countDown()
        }
    }

    /**
     * Feed a raw incoming link (from the launch intent or onNewIntent). Applies the
     * 2s dedup window, publishes the raw event, then dispatches a resolve request;
     * the resolved event is emitted to observers when the backend responds.
     *
     * @param isInitialLink true for the launch link captured during startup.
     * @param source the resolve `source` tag (defaults to `attriax_sdk`).
     */
    fun handleIncomingLink(
        rawUri: String,
        isInitialLink: Boolean,
        source: String = SOURCE_AUTOMATIC,
    ) {
        val uri = AttriaxUri.parse(rawUri) ?: run {
            if (isInitialLink) completeInitialLinkIfAbsent()
            return
        }
        val receivedAt = nowMs()
        if (isDuplicate(uri.toString(), receivedAt)) {
            if (isInitialLink) completeInitialLinkIfAbsent()
            return
        }

        val raw = AttriaxRawDeepLinkEvent(uri = uri, receivedAtMs = receivedAt, isInitial = isInitialLink)
        if (isInitialLink) rawInitialEvent = raw
        val slot = ResolutionSlot()
        val rawRecipients = synchronized(lock) {
            pendingResolutions[raw] = slot
            ArrayList(rawListeners)
        }
        rawRecipients.forEach { it.onRawDeepLink(raw) }

        val metadata = AttriaxDeepLinkResolver.buildResolveMetadata(uri, isInitialLink)
        resolveDispatch(uri, metadata, source, isInitialLink) { data ->
            val trigger = if (isInitialLink) AttriaxDeepLinkTrigger.COLD_START else AttriaxDeepLinkTrigger.FOREGROUND
            val event = if (data != null) {
                val result = AttriaxDeepLinkResolver.decodeResolution(data)
                // Browser handling runs BEFORE building the event so `handledBySdk`
                // reflects the open outcome (PARITY — _buildResolutionWithBrowserHandling).
                val handledBySdk = handleBrowserAction(result.browserAction)
                AttriaxDeepLinkResolver.buildResolution(
                    result = result,
                    clickedAtMs = receivedAt,
                    consumedAtMs = nowMs(),
                    trigger = trigger,
                    fallbackUri = uri,
                    rawEvent = raw,
                    handledBySdk = handledBySdk,
                )
            } else {
                null
            }
            if (isInitialLink) {
                initialEvent = event
                completeInitialLinkIfAbsent()
            }
            if (event != null) emit(event)
            slot.complete(event)
        }
    }

    /**
     * Record a manual deep-link conversion (public `recordDeepLink`). Behaves like
     * an incoming link but with a caller-supplied source + optional metadata and
     * WITHOUT dedup/initial-link probing. Mirrors the Flutter reference
     * `recordManualConversion`, which RETURNS the resolved event (attriax_deep_links
     * .dart:90-94): the resolved event is emitted to observers AND returned here.
     *
     * Blocks (bounded by [timeoutMs]) until resolution completes; returns the
     * resolved event, or null when the resolve failed / was withheld / timed out.
     * Off-main-thread only (matches the facade's blocking convention). A malformed
     * URI returns null immediately.
     */
    fun recordDeepLink(
        rawUri: String,
        metadata: Map<String, Any?>?,
        source: String = SOURCE_MANUAL,
        timeoutMs: Long = DEFAULT_MANUAL_CONVERSION_TIMEOUT_MS,
    ): AttriaxDeepLinkEvent? {
        val uri = AttriaxUri.parse(rawUri) ?: return null
        val receivedAt = nowMs()
        val merged = AttriaxDeepLinkResolver.buildResolveMetadata(uri, isInitialLink = false, extra = metadata)
        val slot = ResolutionSlot()
        resolveDispatch(uri, merged, source, false) { data ->
            val event = if (data != null) {
                val result = AttriaxDeepLinkResolver.decodeResolution(data)
                val handledBySdk = handleBrowserAction(result.browserAction)
                AttriaxDeepLinkResolver.buildResolution(
                    result = result,
                    clickedAtMs = receivedAt,
                    consumedAtMs = nowMs(),
                    trigger = AttriaxDeepLinkTrigger.FOREGROUND,
                    fallbackUri = uri,
                    handledBySdk = handledBySdk,
                )
            } else {
                null
            }
            if (event != null) emit(event)
            slot.complete(event)
        }
        return slot.await(timeoutMs)
    }

    /**
     * Wait for the resolution of a previously-staged raw deep link (PARITY §6 —
     * Flutter `waitResolution` → event-hub `waitForResolution`,
     * attriax_deep_links.dart:46-48). Blocks (bounded by [timeoutMs]) until the raw
     * event's resolution completes and returns the resolved event, or null when it
     * failed / timed out. Off-main-thread only.
     *
     * Semantic difference vs Flutter: an UNKNOWN raw event (never staged) returns
     * null here, whereas Flutter returns an errored future — the KMP facade favors
     * nullable returns over throwing (as `waitForInitialDeepLink` already does).
     */
    fun waitResolution(
        rawEvent: AttriaxRawDeepLinkEvent,
        timeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
    ): AttriaxDeepLinkEvent? {
        val slot = synchronized(lock) { pendingResolutions[rawEvent] } ?: return null
        return slot.await(timeoutMs)
    }

    /**
     * Recover a deferred deep link from the app-open RESPONSE (row DL3). Fires at
     * most ONCE (guarded in-memory for this runtime AND by the persisted flag), and
     * is skipped on `appDataClear`.
     */
    fun handleDeferredAppOpen(openResponseData: Map<String, Any?>?) {
        if (deferredFiredThisRuntime.value) return
        if (readDeferredHandled()) return
        val event = AttriaxDeepLinkDeferredRecovery.recover(openResponseData, nowMs()) ?: return
        if (!deferredFiredThisRuntime.compareAndSet(false, true)) return
        writeDeferredHandled(true)
        emit(event)
    }

    private fun emit(event: AttriaxDeepLinkEvent) {
        // Hold `lock` so a concurrent addListener (which also holds it) cannot both
        // observe this event as latestEvent AND be in the listener list for this
        // emit — otherwise a mid-emit registration would receive the event twice.
        val recipients = synchronized(lock) {
            latestEvent = event
            ArrayList(deepLinkListeners)
        }
        recipients.forEach { it.onDeepLink(event) }
    }

    private fun isDuplicate(uriString: String, nowMs: Long): Boolean = synchronized(lock) {
        val prevUri = lastHandledRaw
        val prevAt = lastHandledAtMs
        lastHandledRaw = uriString
        lastHandledAtMs = nowMs
        prevUri == uriString && nowMs - prevAt < dedupWindowMs
    }

    /**
     * A one-shot resolution result holder (the KMP analog of a Dart `Completer`).
     * Backed by [AttriaxLatch] so a waiter blocks off-main-thread until the resolve
     * callback fires (or the bounded timeout elapses). Idempotent: only the first
     * [complete] wins; late [await] callers read the stored value without blocking.
     */
    private class ResolutionSlot {
        private val slotLock = SynchronizedObject()
        private val latch = AttriaxLatch(1)
        @Volatile private var done = false
        @Volatile private var event: AttriaxDeepLinkEvent? = null

        fun complete(resolved: AttriaxDeepLinkEvent?) {
            val first = synchronized(slotLock) {
                if (done) {
                    false
                } else {
                    event = resolved
                    done = true
                    true
                }
            }
            if (first) latch.countDown()
        }

        fun await(timeoutMs: Long): AttriaxDeepLinkEvent? {
            if (!done) latch.await(timeoutMs)
            return event
        }
    }

    companion object {
        const val DEFAULT_DEDUP_WINDOW_MS = 2_000L
        const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
        /** Upper bound for a manual `recordDeepLink` wait (Flutter's default is 10s). */
        const val DEFAULT_MANUAL_CONVERSION_TIMEOUT_MS = 10_000L
        const val SOURCE_AUTOMATIC = "attriax_sdk"
        const val SOURCE_MANUAL = "manual"
    }
}
