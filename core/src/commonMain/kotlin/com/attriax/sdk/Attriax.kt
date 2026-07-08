package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxBackgroundExecutor
import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.AttriaxIso8601
import com.attriax.sdk.internal.ConnectivityMonitor
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.ResolvedDeviceId
import com.attriax.sdk.internal.attestation.AttriaxAttestationChallengeFetcher
import com.attriax.sdk.internal.attestation.AttriaxAttestationManager
import com.attriax.sdk.internal.attriaxBackgroundExecutor
import com.attriax.sdk.internal.consent.AttriaxConsentManager
import com.attriax.sdk.internal.consent.AttriaxConsentQueuePolicy
import com.attriax.sdk.internal.consent.AttriaxConsentRequestRewrites
import com.attriax.sdk.internal.consent.AttriaxConsentStore
import com.attriax.sdk.internal.consent.AttriaxGdprConsentState
import com.attriax.sdk.internal.consent.AttriaxGdprConsentValues
import com.attriax.sdk.internal.consent.AttriaxHttpConsentTransport
import com.attriax.sdk.internal.consent.AttriaxTrackingSignal
import com.attriax.sdk.internal.dispatch.AttriaxDispatcher
import com.attriax.sdk.internal.queue.AttriaxQueueManager
import com.attriax.sdk.internal.queue.AttriaxQueuedRequest
import com.attriax.sdk.internal.dispatch.AttriaxBatchKeepAlive
import com.attriax.sdk.internal.request.AttriaxApiRequest
import com.attriax.sdk.internal.request.AttriaxEndpoints
import com.attriax.sdk.internal.request.AttriaxRequestBuilders
import com.attriax.sdk.internal.session.AttriaxSessionIdentity
import com.attriax.sdk.internal.session.AttriaxSessionLifecycleEvent
import com.attriax.sdk.internal.session.AttriaxSessionLifecycleManager
import com.attriax.sdk.internal.session.AttriaxSessionManager
import com.attriax.sdk.internal.session.AttriaxSessionSnapshot
import com.attriax.sdk.internal.session.AttriaxSessionSnapshotStore
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Attriax SDK core engine (Epic 9.2 slice 1).
 *
 * This is the composition root that wires the pure engine
 * (config, device identity, queue, retry, batching, dispatcher) to its platform
 * I/O ports. It implements the init → app-open bootstrap (rows I1/O1/O3) and
 * the frozen build-time identity stamping (row D3).
 *
 * Later slices layer tracking richness, consent, deep links, session lifecycle
 * timers, and attestation on top of this engine; those surfaces are intentionally
 * NOT built here.
 */
class Attriax internal constructor(
    private val config: AttriaxConfig,
    private val store: KeyValueStore,
    private val transport: HttpClient,
    private val connectivity: ConnectivityMonitor,
    private val context: AttriaxContextSnapshot,
    private val deviceIdentityStore: AttriaxDeviceIdentityStore,
    private val clock: AttriaxClock = AttriaxClock.SYSTEM,
    /**
     * Heartbeat-timer scheduler (PARITY §3, row S3). Defaults to a no-op scheduler
     * so the pure engine + JVM tests never spin up a real timer; the android factory
     * supplies [com.attriax.sdk.android.AttriaxExecutorScheduler].
     */
    private val scheduler: com.attriax.sdk.internal.AttriaxScheduler =
        NOOP_SCHEDULER,
    /**
     * Foreground/background binder (PARITY §3, row S3). Defaults to no-op; the
     * android factory supplies the ProcessLifecycleOwner-backed binder. A lambda is
     * used to defer construction until [sessionLifecycleManager] exists.
     */
    private val lifecycleBinderFactory:
        (AttriaxSessionLifecycleManager) -> com.attriax.sdk.internal.AttriaxLifecycleBinder =
        { com.attriax.sdk.internal.AttriaxLifecycleBinder.Noop },
    /**
     * Google Play install-referrer seam (PARITY §3 — app-open enrichment). Defaults
     * to [AttriaxInstallReferrerProvider.Unavailable] so the pure engine + JVM tests
     * never touch the Play client; the android factory supplies
     * [com.attriax.sdk.android.AttriaxPlayInstallReferrerProvider].
     */
    private val installReferrerProvider:
        com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerProvider =
        com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerProvider.Unavailable,
    /**
     * Background flush executor (PARITY §7). Injected so the pure engine + tests can
     * substitute a synchronous fake; production wraps a daemon single-thread executor.
     */
    private val flushExecutor: AttriaxBackgroundExecutor = attriaxBackgroundExecutor("attriax-flush"),
    /**
     * Dedicated single-thread executor for background consent sync (PARITY §5). Also
     * passed as the consent manager's `syncExecutor`. Injected for deterministic tests.
     */
    private val consentExecutor: AttriaxBackgroundExecutor = attriaxBackgroundExecutor("attriax-consent"),
) {
    private val queue = AttriaxQueueManager(store, config.maxQueueSize)
    private val dispatcher = AttriaxDispatcher(
        queue = queue,
        transport = transport,
        clock = clock,
        onDelivered = { queued, response -> onRequestDelivered(queued, response) },
        buildSessionKeepAliveBatch = { group -> buildSessionKeepAliveBatch(group) },
        onSessionKeepAliveDelivered = { sessionId, occurredAtMs ->
            sessionLifecycleManager.handleSuccessfulForegroundFlush(sessionId, occurredAtMs)
        },
    )

    // -------- session lifecycle (PARITY §3, rows S2–S5) --------

    private val sessionManager = AttriaxSessionManager(
        clock = clock,
        snapshotStore = AttriaxSessionSnapshotStore(store),
        heartbeatIntervalMs = config.sessionHeartbeatIntervalMs,
        firstLaunchHeartbeatIntervalMs = config.firstLaunchSessionHeartbeatIntervalMs,
        generateSessionId = { com.attriax.sdk.internal.AttriaxIdGenerator.generate() },
    )

    private val sessionLifecycleManager = AttriaxSessionLifecycleManager(
        sessionManager = sessionManager,
        clock = clock,
        scheduler = scheduler,
        isEnabled = { isEnabled() },
        currentIdentity = { currentSessionIdentity() },
        enqueueLifecycle = { event -> enqueueSessionLifecycle(event) },
        requestFlush = { scheduleFlush() },
    )

    private val lifecycleBinder = lifecycleBinderFactory(sessionLifecycleManager)

    /**
     * Device-attestation orchestrator (PARITY §9, rows AT1/AT2). Inert unless
     * [AttriaxConfig.attestationEnabled] is `true`; a `null` provider degrades to
     * the noop. Pure over a challenge-fetch seam and the public provider interface
     * so it is JVM-tested with fakes; the real Play Integrity call lives behind the
     * provider in the android layer.
     */
    private val attestationManager = AttriaxAttestationManager(
        enabled = config.attestationEnabled,
        provider = config.attestationProvider,
        fetchChallenge = { AttriaxAttestationChallengeFetcher(transport).fetch() },
    )

    /**
     * Install-referrer capture policy (PARITY §3). Cache-first + fetch-once-with-
     * one-retry over the [installReferrerProvider] seam; pure and JVM-tested. Inert
     * when the provider is [AttriaxInstallReferrerProvider.Unavailable] or capture
     * is disabled in config.
     */
    private val installReferrer =
        com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerCoordinator(
            provider = installReferrerProvider,
            store = store,
            enabled = config.installReferrerEnabled,
        )

    private val consentManager = AttriaxConsentManager(
        config = config,
        clock = clock,
        consentStore = AttriaxConsentStore(store),
        transport = AttriaxHttpConsentTransport(transport),
        syncExecutor = consentExecutor,
    )

    private val consentQueuePolicy = AttriaxConsentQueuePolicy(
        isWaitingForGdprConsent = { consentManager.isWaitingForGdprConsent },
        anonymousTrackingEnabled = { anonymousTrackingFlag.value },
        allowsAttributionTracking = { consentManager.allowsAttributionTracking() },
        trackingDecisionFor = { consentManager.trackingDecisionFor(it) },
    )

    private val initialized = atomic(false)
    private val appOpenScheduled = atomic(false)
    private val enabledFlag = atomic(true)
    private val anonymousTrackingFlag = atomic(config.anonymousTracking)

    @Volatile private var deviceIdentity: ResolvedDeviceId? = null
    @Volatile private var firstLaunch: Boolean = true

    private val connectivityListener = ConnectivityMonitor.Listener { scheduleFlush() }

    /** Public tracking / revenue / identify surface (PARITY §4). */
    val tracking: AttriaxTracking by lazy { AttriaxTracking(this) }

    /** Public GDPR consent + anonymous-mode surface (PARITY §5). */
    val consent: AttriaxConsent by lazy { AttriaxConsent(this) }

    /** Public deep-link surface (PARITY §6). */
    val deepLinks: AttriaxDeepLinks by lazy { AttriaxDeepLinks(this) }

    /**
     * Pending resolve-response callbacks keyed by queued-request id (PARITY §6, row
     * DL2). Registered before enqueue; fired from [onRequestDelivered] with the
     * decoded response `data` map when the resolve is delivered. Guarded by
     * [pendingResolveLock] (replaces the JVM ConcurrentHashMap).
     */
    private val pendingResolveLock = SynchronizedObject()
    private val pendingResolveCallbacks = mutableMapOf<String, (Map<String, Any?>?) -> Unit>()

    /**
     * Coalesced periodic-flush timer (PARITY §7 — Flutter synchronizer
     * `_deferredFlushTimer`/`_scheduleDeferredFlush`). A single one-shot handle armed
     * when a NON-immediate request is enqueued (and not deferred by consent); it
     * drains the queue one `config.eventFlushIntervalMs` later. Never stacks — a
     * pending handle short-circuits re-arming, and an immediate [scheduleFlush]
     * cancels it. Guarded by [deferredFlushLock].
     */
    private val deferredFlushLock = SynchronizedObject()
    @Volatile private var deferredFlushHandle: com.attriax.sdk.internal.AttriaxScheduler.ScheduledHandle? = null

    /** Deep-link runtime coordinator (dedup / initial-link probe / deferred). */
    private val deepLinkManager = com.attriax.sdk.internal.deeplink.AttriaxDeepLinkManager(
        nowMs = { clock.nowMs() },
        resolveDispatch = { uri, metadata, source, isInitialLink, onResolved ->
            dispatchDeepLinkResolve(uri, metadata, source, isInitialLink, onResolved)
        },
        readDeferredHandled = { store.getString(KEY_DEFERRED_DEEP_LINK_HANDLED) != null },
        writeDeferredHandled = { handled ->
            if (handled) store.putString(KEY_DEFERRED_DEEP_LINK_HANDLED, "true")
            else store.remove(KEY_DEFERRED_DEEP_LINK_HANDLED)
        },
    )

    val isInitialized: Boolean get() = initialized.value
    val isFirstLaunch: Boolean get() = firstLaunch
    val deviceId: String? get() = deviceIdentity?.value

    var enabled: Boolean
        get() = enabledFlag.value
        set(value) { enabledFlag.value = value }

    /**
     * GDPR-safe anonymous-tracking toggle (PARITY §4/§5). The consent-driven
     * capture semantics land in slice 3; here it is a durable in-memory flag the
     * tracking surface reads. Defaults from [AttriaxConfig.anonymousTracking].
     */
    var anonymousTrackingEnabled: Boolean
        get() = anonymousTrackingFlag.value
        set(value) {
            anonymousTrackingFlag.value = value
            consentManager.anonymousTrackingEnabled = value
        }

    // Engine accessors used by the tracking surface (identity is frozen at build
    // time — the surface reads the same resolved identity the engine holds).
    internal val contextSnapshot: AttriaxContextSnapshot get() = context
    internal val resolvedDeviceId: String? get() = deviceIdentity?.value
    internal val resolvedDeviceIdSource: String? get() = deviceIdentity?.source
    internal val isTrackingEnabled: Boolean get() = enabledFlag.value
    internal val projectTokenForTracking: String get() = config.normalizedProjectToken

    /**
     * Bootstrap the runtime (PARITY §1 init sequence):
     *  1. restore persisted state,
     *  2. generate-or-load device id + resolve source,
     *  3. context snapshot is already captured (injected),
     *  4. mark isInitialized,
     *  5. schedule the app-open ONCE per runtime (best-effort, non-blocking).
     */
    fun init() {
        if (!initialized.compareAndSet(false, true)) return

        firstLaunch = store.getString(KEY_FIRST_LAUNCH) == null
        deviceIdentity = deviceIdentityStore.loadOrCreate()
        // Restore persisted consent BEFORE any capture gating runs, and reconcile
        // the queue whenever the consent decision changes (PARITY §5, rows C2/C5).
        consentManager.restore()
        consentManager.onStateChanged = { onConsentStateChanged() }
        connectivity.register(connectivityListener)

        scheduleAppOpenIfNeeded()

        // Session lifecycle (PARITY §3, rows S2–S5): restore/continue-or-start the
        // session snapshot, seed the initial START / recovered END telemetry, and
        // begin foreground/background detection + the heartbeat timer. Gated on
        // sessionTrackingEnabled; identity is frozen at build like every other signal.
        bootstrapSession()

        // Flush any consent decision persisted with pendingSync across a restart.
        consentManager.flushPendingSync()

        if (firstLaunch) {
            store.putString(KEY_FIRST_LAUNCH, "false")
        }
    }

    /**
     * Restore-or-start the session at init and wire lifecycle telemetry (PARITY §3).
     * A replaced session (continuation window exceeded on restore) is seeded as a
     * recovered END (row S5); a freshly-started session seeds the initial START
     * (row S3). Then the lifecycle manager is activated (emits the seeded START +
     * starts the heartbeat) and foreground/background detection is bound.
     */
    private fun bootstrapSession() {
        if (!config.sessionTrackingEnabled) return
        val result = sessionManager.restoreOrStart(currentSessionIdentity())
        if (result.startedNewSession) {
            sessionLifecycleManager.seedInitialSessionStart(result.currentSession)
            sessionLifecycleManager.seedRecoveredSessionEnd(result.replacedSession)
        }
        lifecycleBinder.bind()
        sessionLifecycleManager.activate()
    }

    /** The current-launch identity snapshot fed to the session state machine. */
    private fun currentSessionIdentity(): AttriaxSessionIdentity {
        val identity = deviceIdentity
        return AttriaxSessionIdentity(
            deviceId = identity?.value,
            platform = context.platform,
            appPackageName = context.packageName,
            appVersion = context.appVersion,
            appBuildNumber = context.appBuildNumber,
            locale = context.deviceLocale,
            isFirstLaunch = firstLaunch,
            sdkPackageVersion = context.sdkPackageVersion,
        )
    }

    /** The current session snapshot (PARITY public surface), or null when none is active. */
    val currentSession: AttriaxSessionSnapshot? get() = sessionManager.currentSession

    /**
     * Enqueue an event (thin engine-level entry; the richer public tracking API
     * lives on [tracking]). Throws if called before [init] (row I1).
     */
    fun recordEvent(name: String, eventData: Map<String, Any?>? = null, flushImmediately: Boolean = false) {
        requireInitialized()
        val identity = deviceIdentity
        // Stamp the current session (PARITY §3): events carry the live session id +
        // ms-since-start so the backend correlates them, and so the dispatcher can
        // inject a session keep-alive when a batch carries a live-session event (S4).
        val session = sessionManager.currentSession
        val occurredAtMs = clock.nowMs()
        val request = AttriaxRequestBuilders.buildEvent(
            projectToken = config.normalizedProjectToken,
            eventName = name,
            eventData = eventData,
            deviceId = identity?.value,
            deviceIdSource = identity?.source,
            sessionId = session?.sessionId,
            sessionRelativeTimeMs = session?.sessionRelativeTimeMs(occurredAtMs),
            clientOccurredAtIso = nowIso(),
        )
        enqueueRequest(request, shouldFlushEventImmediately(flushImmediately))
    }

    /**
     * First-launch eager-flush decision (PARITY §7 — Flutter tracking-manager
     * `_shouldFlushEventImmediately`, attriax_tracking_manager.dart:365-376). An
     * explicit `flushImmediately` always wins; otherwise, on the FIRST launch and
     * when [AttriaxConfig.flushEventsImmediatelyOnFirstLaunch] is set, the event is
     * flushed eagerly instead of buffering to the periodic timer. Applies only to
     * the event-family signals Flutter routes through it (events / page views /
     * notifications) — NOT crashes, identify, session, or app-open.
     */
    internal fun shouldFlushEventImmediately(flushImmediately: Boolean): Boolean {
        if (flushImmediately) return true
        return config.flushEventsImmediatelyOnFirstLaunch && firstLaunch
    }

    /**
     * Build the session request for a lifecycle [event] and push it through the
     * SAME consent-gated queue path as every other signal (session is an
     * anon-capable signal — row C4). Identity is stamped from the frozen build-time
     * device id; the full SdkSessionDto context comes from the snapshot.
     */
    private fun enqueueSessionLifecycle(event: AttriaxSessionLifecycleEvent) {
        if (!initialized.value) return
        val request = buildSessionRequest(event)
        enqueueTracked(request, flushImmediately = false)
    }

    private fun buildSessionRequest(event: AttriaxSessionLifecycleEvent): AttriaxApiRequest {
        val session = event.session
        val identity = deviceIdentity
        return AttriaxRequestBuilders.buildSession(
            projectToken = config.normalizedProjectToken,
            kind = event.kind,
            sessionId = session.sessionId,
            deviceId = identity?.value,
            deviceIdSource = identity?.source,
            clientOccurredAtIso = nowIso(event.occurredAtMs),
            sessionRelativeTimeMs = session.sessionRelativeTimeMs(event.occurredAtMs),
            platform = session.platform,
            locale = session.locale,
            isFirstLaunch = session.isFirstLaunch,
            appVersion = session.appVersion,
            appBuildNumber = session.appBuildNumber,
            appPackageName = session.appPackageName,
            sdkApiVersion = context.sdkApiVersion,
            sdkPackageVersion = session.sdkPackageVersion ?: context.sdkPackageVersion,
            metadata = event.metadata,
        )
    }

    /**
     * Session keep-alive injection hook for the dispatcher (PARITY §4, row S4).
     * When a batch [group] carries an EVENT tagged with the live session's id,
     * returns a synthetic HEARTBEAT session request (sharing the batch identity) to
     * append; otherwise null. Mirrors Flutter `_buildSessionKeepAliveBatchRequest`.
     */
    private fun buildSessionKeepAliveBatch(
        group: List<AttriaxQueuedRequest>,
    ): AttriaxBatchKeepAlive? {
        val current = sessionManager.currentSession ?: return null
        val carriesCurrentSessionEvent = group.any { queued ->
            queued.request.kind == AttriaxApiRequest.KIND_TRACK_EVENT &&
                queued.request.body["sessionId"] == current.sessionId
        }
        if (!carriesCurrentSessionEvent) return null

        val heartbeat = sessionLifecycleManager.buildKeepAliveHeartbeat(clock.nowMs()) ?: return null
        val request = buildSessionRequest(heartbeat)
        // The synthetic keep-alive must be batchable (identity present) to share the
        // batch envelope; if identity is stripped (anonymous) it cannot ride along.
        if (!request.isBatchable) return null
        return AttriaxBatchKeepAlive(
            request = request,
            sessionId = heartbeat.session.sessionId,
            occurredAtMs = heartbeat.occurredAtMs,
        )
    }

    /**
     * Consent-aware enqueue gate (PARITY §5, row C4). Replaces the old
     * `enabled`-only gate: every tracking request is now filtered through the
     * consent policy BEFORE it is persisted.
     *
     *  * WITHHELD (capture=false) → the request is dropped (not enqueued).
     *  * ANONYMOUS (capture=true, attachDeviceIdentity=false) → the device identity
     *    is stripped before enqueue so it is sent without device-linked identity.
     *  * IDENTIFIED (capture=true, attachDeviceIdentity=true) → enqueued as built.
     *  * deferNetwork=true (anonymousTracking OFF while waiting) → enqueued but NOT
     *    flushed; it buffers locally until consent allows dispatch.
     *
     * Kinds the consent-signal policy does not classify (user / uninstall token)
     * are attribution-gated: dropped unless attribution tracking is allowed.
     *
     * @return true if the request was enqueued, false if it was withheld/dropped.
     */
    private fun enqueueTracked(
        request: AttriaxApiRequest,
        flushImmediately: Boolean,
    ): Boolean {
        val decision = consentQueuePolicy.trackingDecisionForQueuedRequest(request)
        if (decision != null) {
            if (!decision.capture) return false
            val toEnqueue = if (decision.attachDeviceIdentity) {
                request
            } else {
                AttriaxConsentRequestRewrites.anonymize(request)
            }
            enqueue(toEnqueue)
            // Buffer locally (no flush) when network dispatch must be deferred by
            // consent; otherwise flush now (immediate) or arm the coalesced periodic
            // flush (non-immediate → drains after eventFlushIntervalMs). PARITY §7.
            if (!decision.deferNetwork) scheduleFlushOrDefer(flushImmediately)
            return true
        }

        // Identity-linked kinds not covered by the signal policy: user / uninstall
        // token require attribution consent. Dynamic links are always allowed.
        val allowed = when (request.kind) {
            AttriaxApiRequest.KIND_USER,
            AttriaxApiRequest.KIND_REGISTER_UNINSTALL_TOKEN ->
                consentManager.allowsAttributionTracking()
            else -> true
        }
        if (!allowed) return false
        enqueue(request)
        scheduleFlushOrDefer(flushImmediately)
        return true
    }

    /**
     * Enqueue a pre-built request through the frozen-identity queue path and
     * optionally kick a flush (PARITY §4/§7). Shared by the [tracking] surface so
     * events/crashes/notifications/user updates all traverse the same engine.
     */
    internal fun enqueueRequest(
        request: com.attriax.sdk.internal.request.AttriaxApiRequest,
        flushImmediately: Boolean,
    ) {
        requireInitialized()
        enqueueTracked(request, flushImmediately)
    }

    /**
     * Direct (non-queued) receipt validation (PARITY §4). Works even when tracking
     * is disabled / consent is unresolved because it bypasses the queue and the
     * enabled gate entirely — it is a synchronous request/response, not a fire-and-
     * forget signal. Returns the decoded response payload (envelope already
     * unwrapped by the transport), or throws the transport exception on failure.
     *
     * Must be called off the main thread (it performs blocking I/O).
     */
    fun validateReceipt(
        receipt: String,
        test: Boolean = false,
        provider: String? = null,
        environment: String? = null,
        productId: String? = null,
        transactionId: String? = null,
    ): Any? {
        requireInitialized()
        val normalizedReceipt = receipt.trim()
        require(normalizedReceipt.isNotEmpty()) { "receipt must not be empty." }
        val body = AttriaxRequestBuilders.buildReceiptValidate(
            projectToken = config.normalizedProjectToken,
            receipt = normalizedReceipt,
            deviceId = deviceIdentity?.value,
            clientOccurredAtIso = nowIso(),
            provider = com.attriax.sdk.internal.AttriaxRevenue.trimOrNull(provider),
            environment = com.attriax.sdk.internal.AttriaxRevenue.trimOrNull(environment),
            transactionId = com.attriax.sdk.internal.AttriaxRevenue.trimOrNull(transactionId),
            productId = com.attriax.sdk.internal.AttriaxRevenue.trimOrNull(productId),
            test = test,
        )
        val response = transport.post(
            com.attriax.sdk.internal.request.AttriaxEndpoints.RECEIPTS_VALIDATE,
            com.attriax.sdk.internal.json.Json.encode(body),
        )
        return response.body?.let { com.attriax.sdk.internal.json.Json.decode(it) }
    }

    /** Best-effort flush kicked onto the background executor. */
    fun flush() {
        scheduleFlush()
    }

    // -------- deep links (PARITY §6, rows DL1–DL4) — engine methods behind `deepLinks` --------

    internal val latestDeepLink: AttriaxDeepLinkEvent? get() = deepLinkManager.latestDeepLink
    internal val initialDeepLink: AttriaxDeepLinkEvent? get() = deepLinkManager.initialDeepLink
    internal val rawInitialDeepLink: AttriaxRawDeepLinkEvent? get() = deepLinkManager.rawInitialDeepLink
    internal val isInitialDeepLinkResolved: Boolean get() = deepLinkManager.isInitialDeepLinkResolved

    internal fun addDeepLinkListener(listener: AttriaxDeepLinkListener) =
        deepLinkManager.addListener(listener)
    internal fun removeDeepLinkListener(listener: AttriaxDeepLinkListener) =
        deepLinkManager.removeListener(listener)
    internal fun addRawDeepLinkListener(listener: AttriaxRawDeepLinkListener) =
        deepLinkManager.addRawListener(listener)
    internal fun removeRawDeepLinkListener(listener: AttriaxRawDeepLinkListener) =
        deepLinkManager.removeRawListener(listener)

    internal fun handleIncomingDeepLink(rawUri: String, isInitialLink: Boolean) {
        requireInitialized()
        deepLinkManager.handleIncomingLink(rawUri, isInitialLink = isInitialLink)
    }

    internal fun completeInitialDeepLinkIfAbsent() =
        deepLinkManager.completeInitialLinkIfAbsent()

    internal fun waitForInitialDeepLink(): AttriaxDeepLinkEvent? =
        deepLinkManager.waitForInitialDeepLink()

    internal fun recordDeepLink(uri: String, metadata: Map<String, Any?>?, source: String) {
        requireInitialized()
        deepLinkManager.recordDeepLink(uri, metadata, source)
    }

    /**
     * Create a short dynamic link (PARITY §6). Sent DIRECTLY (non-queued) — it is a
     * synchronous request/response, so it works even while tracking is deferred.
     * Blocking I/O — call off the main thread.
     */
    internal fun createDynamicLink(
        name: String?,
        destinationUrl: String?,
        group: String?,
        prefix: String?,
        socialPreview: AttriaxDynamicLinkSocialPreview?,
        utms: AttriaxDynamicLinkUtms?,
        redirects: AttriaxDynamicLinkRedirects?,
        data: Map<String, Any?>?,
    ): AttriaxCreateDynamicLinkResult {
        requireInitialized()
        val body = AttriaxRequestBuilders.buildCreateDynamicLink(
            projectToken = config.normalizedProjectToken,
            name = name,
            destinationUrl = destinationUrl,
            group = group,
            prefix = prefix,
            iosRedirect = redirects?.ios,
            androidRedirect = redirects?.android,
            previewTitle = socialPreview?.title,
            previewDescription = socialPreview?.description,
            utmSource = utms?.source,
            utmMedium = utms?.medium,
            utmCampaign = utms?.campaign,
            utmTerm = utms?.term,
            utmContent = utms?.content,
            data = data,
        )
        val response = transport.post(
            AttriaxEndpoints.DYNAMIC_LINKS,
            com.attriax.sdk.internal.json.Json.encode(body),
        )
        val decoded = response.body?.let {
            com.attriax.sdk.internal.json.Json.decode(it)
        } as? Map<*, *>
            ?: throw IllegalStateException("Attriax dynamic-link response was empty.")
        return parseDynamicLinkResult(decoded)
    }

    /**
     * Dispatch a deep-link resolve (PARITY §6, row DL2). Builds the DTO with the
     * consent-aware identity decision (deep-link diagnostics are anon-capable while
     * waiting), registers the resolution callback under the queued id, and enqueues
     * through the same consent gate + terminal-drop-exempt dispatcher as every other
     * request. When the resolve is delivered, [onRequestDelivered] fires the callback.
     */
    private fun dispatchDeepLinkResolve(
        uri: com.attriax.sdk.internal.deeplink.AttriaxUri,
        metadata: Map<String, Any?>,
        source: String,
        isInitialLink: Boolean,
        onResolved: (Map<String, Any?>?) -> Unit,
    ) {
        val decision = consentManager.trackingDecisionFor(AttriaxTrackingSignal.DEEP_LINK)
        if (!decision.capture) {
            onResolved(null)
            return
        }
        val attachIdentity = decision.attachDeviceIdentity
        val identity = deviceIdentity
        val request = AttriaxRequestBuilders.buildResolveDeepLink(
            projectToken = config.normalizedProjectToken,
            platform = context.platform,
            source = source,
            isFirstLaunch = firstLaunch,
            deviceId = if (attachIdentity) identity?.value else null,
            deviceIdSource = if (attachIdentity) identity?.source else null,
            rawUrl = uri.toString(),
            linkPath = com.attriax.sdk.internal.deeplink.AttriaxDeepLinkResolver.extractLinkPathFromUri(uri),
            sessionId = null,
            sessionRelativeTimeMs = null,
            metadata = metadata,
        )
        val id = com.attriax.sdk.internal.AttriaxIdGenerator.generate()
        synchronized(pendingResolveLock) { pendingResolveCallbacks[id] = onResolved }
        queue.enqueue(
            AttriaxQueuedRequest(
                id = id,
                request = request,
                createdAtMs = clock.nowMs(),
            ),
        )
        // Deep-link resolve is anon-capable; flush unless network dispatch is deferred.
        if (!decision.deferNetwork) scheduleFlush()
    }

    /**
     * Delivery callback from the dispatcher (single-send only). Routes app-open
     * responses to deferred deep-link recovery (row DL3) and resolve responses to
     * their pending resolution callback (row DL2). Best-effort — never crash a flush.
     */
    private fun onRequestDelivered(
        queued: AttriaxQueuedRequest,
        response: com.attriax.sdk.internal.HttpResponse,
    ) {
        try {
            when (queued.request.kind) {
                AttriaxApiRequest.KIND_OPEN -> {
                    val data = decodeResponseObject(response)
                    deepLinkManager.handleDeferredAppOpen(data)
                }
                AttriaxApiRequest.KIND_RESOLVE_DEEP_LINK -> {
                    val callback = synchronized(pendingResolveLock) {
                        pendingResolveCallbacks.remove(queued.id)
                    }
                    callback?.invoke(decodeResponseObject(response))
                }
                else -> Unit
            }
        } catch (e: Exception) {
            // Best-effort: recovery/emit failure must never crash the host or flush.
        }
    }

    private fun decodeResponseObject(
        response: com.attriax.sdk.internal.HttpResponse,
    ): Map<String, Any?>? {
        val body = response.body ?: return null
        @Suppress("UNCHECKED_CAST")
        return com.attriax.sdk.internal.json.Json.decode(body) as? Map<String, Any?>
    }

    private fun parseDynamicLinkResult(decoded: Map<*, *>): AttriaxCreateDynamicLinkResult {
        // The transport unwraps the `{data:...}` envelope, so `decoded` is the
        // SdkCreateDynamicLinkResponseDto: `{ requestVersion, acceptedAt, link }`.
        val link = decoded["link"] as? Map<*, *>
            ?: throw IllegalStateException("Attriax dynamic-link response missing `link`.")
        @Suppress("UNCHECKED_CAST")
        val linkData = link["data"] as? Map<String, Any?>
        val record = AttriaxDynamicLinkRecord(
            id = link["id"]?.toString().orEmpty(),
            path = link["path"]?.toString().orEmpty(),
            shortUrl = link["shortUrl"]?.toString().orEmpty(),
            name = link["name"] as? String,
            destinationUrl = link["destinationUrl"] as? String,
            group = link["group"] as? String,
            prefix = link["prefix"] as? String,
            data = linkData,
        )
        return AttriaxCreateDynamicLinkResult(shortUrl = record.shortUrl, record = record)
    }

    /** UTC ISO-8601 timestamp for the current clock reading (exposed to [tracking]). */
    internal fun nowIsoNow(): String = nowIso()

    // -------- consent (PARITY §5) — engine methods behind the `consent.gdpr` surface --------

    internal val gdprConsentState: AttriaxGdprConsentState get() = consentManager.gdprConsentState
    internal val gdprConsentValues: AttriaxGdprConsentValues? get() = consentManager.gdprConsentValues
    internal val isWaitingForGdprConsent: Boolean get() = consentManager.isWaitingForGdprConsent

    internal fun needsGdprConsent(localOnly: Boolean): Boolean {
        requireInitialized()
        return consentManager.needsConsent(localOnly = localOnly)
    }

    internal fun setGdprConsent(analytics: Boolean, attribution: Boolean, adEvents: Boolean) {
        requireInitialized()
        consentManager.setConsent(analytics = analytics, attribution = attribution, adEvents = adEvents)
    }

    internal fun setGdprConsentNotRequired() {
        requireInitialized()
        consentManager.setNotRequired()
    }

    internal fun resetGdprConsent() {
        requireInitialized()
        consentManager.reset()
    }

    /**
     * Request GDPR data erasure (PARITY §5, row C5 erase). Sends the deviceId to
     * `/api/sdk/v1/privacy/gdpr/erase` (the ONLY consent-family endpoint that
     * carries the deviceId — the check/upsert bodies never do), then resets the
     * SDK to pre-init on success. Blocking I/O — call off the main thread.
     */
    internal fun requestGdprDataErasure() {
        requireInitialized()
        val deviceId = deviceIdentity?.value
            ?: throw IllegalStateException("Attriax device identity is unavailable. Call init() first.")
        transport.post(
            AttriaxEndpoints.GDPR_ERASE,
            com.attriax.sdk.internal.json.Json.encode(
                linkedMapOf<String, Any?>(
                    AttriaxApiRequest.FIELD_PROJECT_TOKEN to config.normalizedProjectToken,
                    AttriaxApiRequest.FIELD_DEVICE_ID to deviceId,
                ),
            ),
        )
        reset()
    }

    /**
     * Consent-resolution queue reconciliation (PARITY §5, row C5). Runs the three
     * passes over the persisted queue whenever the consent decision changes and we
     * are no longer waiting: (1) IDENTIFY anonymous requests now that identified
     * tracking is allowed, (2) ANONYMIZE denied-but-anonymous-capable requests,
     * (3) DISCARD now-disallowed requests (reason `gdpr_consent_denied`). Runs on
     * the consent executor so it is serialized against the sync loop.
     */
    private fun onConsentStateChanged() {
        if (!initialized.value || !enabledFlag.value) return
        consentExecutor.execute {
            try {
                reconcileQueueForConsent()
            } catch (e: Exception) {
                // Best-effort: a reconciliation failure must never crash the host.
            }
            if (!consentManager.shouldDeferNetworkDispatch) {
                consentManager.flushPendingSync()
                scheduleFlush()
            }
        }
    }

    private fun reconcileQueueForConsent() {
        if (!config.gdprEnabled || consentManager.isWaitingForGdprConsent) return

        val identity = deviceIdentity
        if (identity != null) {
            // PASS 1: attach identity to anonymous requests now allowed identified.
            queue.rewriteWhere { entry ->
                if (consentQueuePolicy.shouldIdentifyQueuedRequestForResolvedConsent(entry.request)) {
                    AttriaxConsentRequestRewrites
                        .identify(entry.request, identity.value, identity.source)
                        ?.let { entry.copy(request = it) }
                } else {
                    null
                }
            }
        }

        // PASS 2: strip identity from denied-but-anonymous-capable requests.
        queue.rewriteWhere { entry ->
            if (consentQueuePolicy.shouldAnonymizeQueuedRequest(entry.request)) {
                entry.copy(request = AttriaxConsentRequestRewrites.anonymize(entry.request))
            } else {
                null
            }
        }

        // PASS 3: discard now-disallowed requests (reason gdpr_consent_denied).
        queue.discardWhere { entry ->
            !consentQueuePolicy.isRequestAllowedByResolvedConsent(entry.request)
        }
    }

    /** Clear SDK state to pre-init (PARITY §1 reset; rows D2). */
    fun reset() {
        // Tear down session telemetry BEFORE clearing identity so no in-flight
        // heartbeat/transition re-persists a snapshot after the wipe (PARITY §3).
        lifecycleBinder.unbind()
        sessionLifecycleManager.reset()
        sessionManager.reset()
        cancelDeferredFlush()
        deviceIdentityStore.clear()
        store.remove(KEY_FIRST_LAUNCH)
        store.remove(KEY_DEFERRED_DEEP_LINK_HANDLED)
        queue.writeAll(emptyList())
        synchronized(pendingResolveLock) { pendingResolveCallbacks.clear() }
        consentManager.clearMemory()
        AttriaxConsentStore(store).clear()
        deviceIdentity = null
        firstLaunch = true
        appOpenScheduled.value = false
        initialized.value = false
    }

    fun dispose() {
        lifecycleBinder.unbind()
        sessionLifecycleManager.deactivate()
        cancelDeferredFlush()
        connectivity.unregister(connectivityListener)
        flushExecutor.shutdown()
        consentExecutor.shutdown()
    }

    // -------- internals --------

    private fun scheduleAppOpenIfNeeded() {
        if (!appOpenScheduled.compareAndSet(false, true)) return
        if (!isEnabled()) return
        if (deviceIdentity == null) return

        // Attestation (PARITY §9) and install-referrer capture (PARITY §3) are both
        // blocking I/O — they must NOT run on the init thread. When NEITHER needs the
        // network this launch, keep the synchronous fast path (re-attaching any cached
        // referrer) so there is zero behavior change for the common case. Otherwise
        // resolve both on the background executor before building the open.
        val referrerNeedsFetch = installReferrer.needsFetch()
        if (!attestationManager.isEnabled && !referrerNeedsFetch) {
            buildAndEnqueueAppOpen(attestation = null, referrer = installReferrer.cachedDetails())
            return
        }
        if (flushExecutor.isShutdown) return
        flushExecutor.execute {
            // Both resolvers degrade to null internally and never throw; the
            // belt-and-braces catch guarantees the open is still built + enqueued.
            val referrer = if (referrerNeedsFetch) {
                installReferrer.fetchAndPersist()
            } else {
                installReferrer.cachedDetails()
            }
            try {
                val envelope =
                    if (attestationManager.isEnabled) attestationManager.resolveEnvelope() else null
                buildAndEnqueueAppOpen(attestation = envelope, referrer = referrer)
            } catch (e: Exception) {
                buildAndEnqueueAppOpen(attestation = null, referrer = referrer)
            }
        }
    }

    /**
     * Build the app-open with the (optional) attestation envelope and enqueue it,
     * then flush when attribution dispatch is allowed. Shared by the synchronous
     * (attestation-disabled) and background (attestation-enabled) paths so the
     * enqueue/hoist/flush semantics are identical (PARITY §3/§5/§9).
     */
    private fun buildAndEnqueueAppOpen(
        attestation: Map<String, Any?>?,
        referrer: com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerDetails?,
    ) {
        val identity = deviceIdentity ?: return
        val open = AttriaxRequestBuilders.buildOpen(
            projectToken = config.normalizedProjectToken,
            context = context,
            deviceId = identity.value,
            deviceIdSource = identity.source,
            isFirstLaunch = firstLaunch,
            sessionId = null,
            sessionStartedAtIso = null,
            installReferrer = referrer?.rawReferrer,
            installBeginTimestampSeconds = referrer?.installBeginTimestampSeconds,
            referrerClickTimestampSeconds = referrer?.referrerClickTimestampSeconds,
            googlePlayInstantParam = referrer?.googlePlayInstantParam,
            attestation = attestation,
            sdkMetadata = config.sdkMetadata,
        )
        enqueue(open)
        // App-open carries attribution/install-referrer data (attribution-linked).
        // Enqueue always (so it is reconciled/hoisted later), but only flush it to
        // the network once attribution tracking is actually allowed — otherwise it
        // buffers until consent resolves (PARITY §3/§5).
        if (allowsAppOpenDispatch()) scheduleFlush()
    }

    /** Whether the app-open may be dispatched under the current consent state. */
    private fun allowsAppOpenDispatch(): Boolean =
        !config.gdprEnabled || consentManager.allowsAttributionTracking()

    private fun enqueue(request: com.attriax.sdk.internal.request.AttriaxApiRequest) {
        queue.enqueue(
            AttriaxQueuedRequest(
                id = com.attriax.sdk.internal.AttriaxIdGenerator.generate(),
                request = request,
                createdAtMs = clock.nowMs(),
            ),
        )
    }

    /**
     * Flush now when [flushImmediately], else arm the coalesced periodic flush
     * (PARITY §7 — Flutter synchronizer `enqueue`: `flushImmediately || interval==0`
     * → `scheduleFlush`, otherwise `_scheduleDeferredFlush`).
     */
    private fun scheduleFlushOrDefer(flushImmediately: Boolean) {
        if (flushImmediately) scheduleFlush() else scheduleDeferredFlush()
    }

    /**
     * Arm a single coalesced flush after `config.eventFlushIntervalMs` (PARITY §7 —
     * Flutter `_scheduleDeferredFlush`). Coalescing: a pending handle short-circuits
     * re-arming so timers never stack; the interval-zero case degrades to an
     * immediate flush (mirrors Flutter's `interval == Duration.zero`). Respects the
     * same [isEnabled] gate as [scheduleFlush] so nothing is scheduled while tracking
     * is disabled / the project token is empty.
     */
    private fun scheduleDeferredFlush() {
        if (config.eventFlushIntervalMs <= 0L) {
            scheduleFlush()
            return
        }
        if (!isEnabled()) return
        synchronized(deferredFlushLock) {
            if (deferredFlushHandle != null) return
            deferredFlushHandle = scheduler.scheduleOnce(config.eventFlushIntervalMs) {
                synchronized(deferredFlushLock) { deferredFlushHandle = null }
                scheduleFlush()
            }
        }
    }

    private fun cancelDeferredFlush() {
        synchronized(deferredFlushLock) {
            deferredFlushHandle?.cancel()
            deferredFlushHandle = null
        }
    }

    private fun scheduleFlush() {
        // An immediate flush supersedes any pending coalesced flush (PARITY §7 —
        // Flutter `scheduleFlush` cancels `_deferredFlushTimer`).
        cancelDeferredFlush()
        if (!isEnabled()) return
        if (flushExecutor.isShutdown) return
        flushExecutor.execute {
            try {
                dispatcher.flush()
            } catch (e: Exception) {
                // Best-effort; a flush failure must never crash the host app.
            }
        }
    }

    private fun isEnabled(): Boolean = enabledFlag.value && config.normalizedProjectToken.isNotEmpty()

    private fun requireInitialized() {
        check(initialized.value) { "Attriax.init() must complete before tracking calls." }
    }

    private fun nowIso(atMs: Long = clock.nowMs()): String = AttriaxIso8601.formatUtcMillis(atMs)

    companion object {
        private const val KEY_FIRST_LAUNCH = "attriax.first_launch_completed"
        private const val KEY_DEFERRED_DEEP_LINK_HANDLED = "attriax.deferred_deep_link_handled"

        /**
         * A scheduler that never fires (used by the pure engine + JVM tests). The
         * android factory injects a real [com.attriax.sdk.android.AttriaxExecutorScheduler].
         */
        private val NOOP_SCHEDULER = object : com.attriax.sdk.internal.AttriaxScheduler {
            override fun schedulePeriodic(
                intervalMs: Long,
                action: () -> Unit,
            ): com.attriax.sdk.internal.AttriaxScheduler.ScheduledHandle =
                com.attriax.sdk.internal.AttriaxScheduler.ScheduledHandle { }

            override fun scheduleOnce(
                delayMs: Long,
                action: () -> Unit,
            ): com.attriax.sdk.internal.AttriaxScheduler.ScheduledHandle =
                com.attriax.sdk.internal.AttriaxScheduler.ScheduledHandle { }
        }
    }
}
