package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxBackgroundExecutor
import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxCrashReportingManager
import com.attriax.sdk.internal.AttriaxUncaughtHandlerRegistration
import com.attriax.sdk.internal.attriaxExceptionName
import com.attriax.sdk.internal.attriaxInstallUncaughtExceptionHandler
import com.attriax.sdk.internal.attriaxRedactUrl
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.AttriaxIso8601
import com.attriax.sdk.internal.AttriaxLogger
import com.attriax.sdk.internal.AttriaxSynchronizationStateHolder
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
import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.AttriaxIdGenerator
import com.attriax.sdk.internal.AttriaxRevenue
import com.attriax.sdk.internal.AttriaxScheduler
import com.attriax.sdk.internal.AttriaxLifecycleBinder
import com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerProvider
import com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerCoordinator
import com.attriax.sdk.internal.deeplink.AttriaxDeepLinkManager
import com.attriax.sdk.internal.deeplink.AttriaxDeepLinkResolver
import com.attriax.sdk.internal.deeplink.AttriaxUri
import com.attriax.sdk.internal.skan.AttriaxSkanCvConfigDecoder
import com.attriax.sdk.internal.skan.AttriaxSkanEngine
import com.attriax.sdk.internal.asa.AttriaxAsaTokenManager
import com.attriax.sdk.internal.referrer.AttriaxReferrerCoordinator
import com.attriax.sdk.internal.attriaxAttStatus
import com.attriax.sdk.internal.attriaxRequestAttAuthorization
import com.attriax.sdk.internal.attriaxSkanSupported
import com.attriax.sdk.internal.attriaxUpdatePostbackConversionValue
import com.attriax.sdk.internal.attriaxFetchAsaAttributionToken

/**
 * Attriax SDK core engine.
 *
 * This is the composition root that wires the pure engine
 * (config, device identity, queue, retry, batching, dispatcher) to its platform
 * I/O ports. It implements the init → app-open bootstrap and
 * the frozen build-time identity stamping.
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
     * Heartbeat-timer scheduler. Defaults to a no-op scheduler
     * so the pure engine + JVM tests never spin up a real timer; the android factory
     * supplies [com.attriax.sdk.android.AttriaxExecutorScheduler].
     */
    private val scheduler: AttriaxScheduler =
        NOOP_SCHEDULER,
    /**
     * Foreground/background binder. Defaults to no-op; the
     * android factory supplies the ProcessLifecycleOwner-backed binder. A lambda is
     * used to defer construction until [sessionLifecycleManager] exists.
     */
    private val lifecycleBinderFactory:
        (AttriaxSessionLifecycleManager) -> AttriaxLifecycleBinder =
        { AttriaxLifecycleBinder.Noop },
    /**
     * Google Play install-referrer seam (app-open enrichment). Defaults
     * to [AttriaxInstallReferrerProvider.Unavailable] so the pure engine + JVM tests
     * never touch the Play client; the android factory supplies
     * [com.attriax.sdk.android.AttriaxPlayInstallReferrerProvider].
     */
    private val installReferrerProvider:
        AttriaxInstallReferrerProvider =
        AttriaxInstallReferrerProvider.Unavailable,
    /**
     * Background flush executor. Injected so the pure engine + tests can
     * substitute a synchronous fake; production wraps a daemon single-thread executor.
     */
    private val flushExecutor: AttriaxBackgroundExecutor = attriaxBackgroundExecutor("attriax-flush"),
    /**
     * Dedicated single-thread executor for background consent sync. Also
     * passed as the consent manager's `syncExecutor`. Injected for deterministic tests.
     */
    private val consentExecutor: AttriaxBackgroundExecutor = attriaxBackgroundExecutor("attriax-consent"),
    /**
     * OS uncaught-exception handler seam. Injected with the platform
     * default so the pure engine + tests can substitute a fake that captures the
     * `onFatalCrash` callback and drives it directly (never crashing the test VM).
     * On native this default is a compile-only placeholder that installs nothing.
     */
    private val installUncaughtExceptionHandler:
        (onFatalCrash: (Throwable) -> Unit) -> AttriaxUncaughtHandlerRegistration =
        ::attriaxInstallUncaughtExceptionHandler,
    /**
     * Browser-open seam for deep-link browser-fallback URLs. Defaults to
     * [AttriaxBrowserOpener.Unavailable] (no-op) so the pure engine + jvm/native never
     * open anything; the android factory injects an ACTION_VIEW-backed opener. Gated
     * by [AttriaxConfig.automaticBrowserHandling] before it is ever called.
     */
    private val browserOpener: AttriaxBrowserOpener = AttriaxBrowserOpener.Unavailable,
    /**
     * Apple ATT status seam. Reads the current tracking-authorization
     * status WITHOUT prompting. Defaults to the platform `attriaxAttStatus` expect
     * fun (the real `ATTrackingManager` status on Apple; UNKNOWN off-Apple). Injected
     * so commonTest can substitute a fake. Only consulted when no wrapper-supplied
     * status is present.
     */
    private val attStatusProvider: () -> AttriaxAttStatus =
        { attriaxAttStatus() },
    /**
     * Apple ATT authorization-request seam. Prompts for authorization
     * (Apple) and returns the resulting status; blocking with an optional timeout.
     * Defaults to the platform `attriaxRequestAttAuthorization` expect fun (the real
     * `ATTrackingManager` prompt on Apple; a no-op returning UNKNOWN off-Apple).
     * Injected so commonTest can assert init invokes it when
     * [AttriaxConfig.requestTrackingAuthorizationOnInit] is `true`.
     */
    private val requestAttAuthorizationSeam: (Long?) -> AttriaxAttStatus =
        { timeoutMs -> attriaxRequestAttAuthorization(timeoutMs) },
    /**
     * SKAdNetwork support probe seam. Defaults to the platform
     * `attriaxSkanSupported` expect fun (`false` on every currently-built target; the
     * future iosMain actual returns `true`). Injected so commonTest can simulate iOS.
     */
    private val skanSupportedSeam: () -> Boolean =
        { attriaxSkanSupported() },
    /**
     * On-device SKAN conversion-value update seam. Defaults to the platform
     * `attriaxUpdatePostbackConversionValue` expect fun (NOT_SUPPORTED off-iOS; the
     * future iosMain actual calls StoreKit `updatePostbackConversionValue`). Injected
     * so commonTest can assert the facade reaches it with the resolved fine/coarse/lock.
     */
    private val updatePostbackConversionValueSeam: (
        fineValue: Int,
        coarseValue: AttriaxSkanCoarseValue,
        lockWindow: Boolean,
    ) -> AttriaxSkanUpdateResult =
        { fineValue, coarseValue, lockWindow ->
            attriaxUpdatePostbackConversionValue(fineValue, coarseValue, lockWindow)
        },
    /**
     * Apple Search Ads (AdServices) attribution-token fetch seam. Defaults
     * to the platform `attriaxFetchAsaAttributionToken` expect fun (`null` off-iOS; the
     * future iosMain actual calls `AAAttribution.attributionToken`). Injected so
     * commonTest can drive the auto-capture path with a fake token.
     */
    private val asaTokenFetchSeam: () -> String? =
        { attriaxFetchAsaAttributionToken() },
) {
    /**
     * Leveled logger (Flutter reference `AttriaxLogger`). Constructed once
     * from [AttriaxConfig.enableDebugLogs] and shared with the public surfaces (the
     * tracking surface logs invalid-currency warnings through it).
     */
    internal val logger = AttriaxLogger(config.enableDebugLogs)

    /**
     * Runtime synchronization state (Flutter reference `AttriaxSynchronizer`).
     * The engine drives it from the real dispatch lifecycle below (flush entry/exit,
     * terminal drops, consent-defer gate, enabled/reset). Exposed via [synchronization].
     */
    private val syncState = AttriaxSynchronizationStateHolder()

    /**
     * Set (during a flush, by the dispatcher `onDropped` callback) when a request is
     * permanently dropped this flush pass, so [resolveSynchronizationStateAfterFlush]
     * can report [AttriaxSynchronizationState.FAILED]. Reset at each flush entry. Only
     * touched on the single-threaded flush executor.
     */
    private val terminalDropDuringFlush = atomic(false)

    private val queue = AttriaxQueueManager(store, config.maxQueueSize)
    private val dispatcher = AttriaxDispatcher(
        queue = queue,
        transport = transport,
        clock = clock,
        logger = logger,
        onDelivered = { queued, response -> onRequestDelivered(queued, response) },
        buildSessionKeepAliveBatch = { group -> buildSessionKeepAliveBatch(group) },
        onSessionKeepAliveDelivered = { sessionId, occurredAtMs ->
            sessionLifecycleManager.handleSuccessfulForegroundFlush(sessionId, occurredAtMs)
        },
        onDropped = { _, _ -> terminalDropDuringFlush.value = true },
    )

    // -------- session lifecycle --------

    private val sessionManager = AttriaxSessionManager(
        clock = clock,
        snapshotStore = AttriaxSessionSnapshotStore(store),
        heartbeatIntervalMs = config.sessionHeartbeatIntervalMs,
        firstLaunchHeartbeatIntervalMs = config.firstLaunchSessionHeartbeatIntervalMs,
        generateSessionId = { AttriaxIdGenerator.generate() },
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
     * Device-attestation orchestrator. Inert unless
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
     * Install-referrer capture policy. Cache-first + fetch-once-with-
     * one-retry over the [installReferrerProvider] seam; pure and JVM-tested. Inert
     * when the provider is [AttriaxInstallReferrerProvider.Unavailable] or capture
     * is disabled in config.
     */
    private val installReferrer =
        AttriaxInstallReferrerCoordinator(
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

    /**
     * Automatic crash reporting. Reuses the engine's crash builder so the
     * replayed/reported crash is the SAME wire shape as the manual [recordError]. The
     * [AttriaxConfig.automaticCrashReportingEnabled] flag (default `true`, matching
     * Flutter) is wired here: when off, no handler installs and no replay runs. On
     * targets without an OS-level uncaught hook (desktop-native) the manager logs a
     * one-time info that auto-capture is unavailable — see its `install()` KDoc.
     */
    private val crashReporting = AttriaxCrashReportingManager(
        enabled = config.automaticCrashReportingEnabled,
        store = store,
        enqueueCrash = { request -> enqueueRequest(request, flushImmediately = false) },
        buildFatalCrash = { throwable ->
            buildCrashRequest(
                error = throwable,
                stackTrace = null,
                fatal = true,
                source = AttriaxCrashReportingManager.SOURCE_UNCAUGHT_EXCEPTION,
                reason = null,
                metadata = null,
            )
        },
        installUncaughtHandler = installUncaughtExceptionHandler,
        logError = { logger.warn(it) },
        logInfo = { logger.info(it) },
    )

    /**
     * SKAdNetwork conversion-value engine. Pure over the support + on-device
     * update seams; StoreKit passthrough (no local rules engine — see [AttriaxSkan]).
     * Config `enabled` gates updates; off-iOS the seams report unsupported.
     */
    private val skanEngine = AttriaxSkanEngine(
        config = config.skan ?: AttriaxSkanConfig(),
        supported = skanSupportedSeam,
        performUpdate = updatePostbackConversionValueSeam,
    )

    /**
     * Apple Search Ads (AdServices) token capture. Best-effort + fully
     * fault-isolated (mirrors the Flutter reference): the auto path fetches the token
     * via the platform seam (null off-iOS → no submission) and POSTs `{projectToken,
     * token}`; the wrapper-supply [submitAsaToken] POSTs an explicitly supplied token
     * on any platform. A send failure never affects init or session.
     */
    private val asaTokenManager = AttriaxAsaTokenManager(
        enabled = config.asaTokenCaptureEnabled,
        acquireToken = asaTokenFetchSeam,
        sendToken = { token -> postAsaToken(token) },
        logError = { error ->
            logger.warn("Apple Search Ads token capture failed; continuing without a report: ${error.message}")
        },
    )

    private val initialized = atomic(false)
    private val appOpenScheduled = atomic(false)
    private val enabledFlag = atomic(true)
    private val anonymousTrackingFlag = atomic(config.anonymousTracking)

    @Volatile private var deviceIdentity: ResolvedDeviceId? = null
    @Volatile private var firstLaunch: Boolean = true

    /**
     * Wrapper-supplied Apple ATT status. Seeded from
     * [AttriaxConfig.attStatus]; updated by [setAttStatus] (or a latched
     * [requestAttAuthorization] result). When non-null it WINS over the platform
     * seam. See [attStatus] / [resolveAttStatusWire].
     */
    @Volatile private var wrapperAttStatus: AttriaxAttStatus? = config.attStatus

    /**
     * Wrapper-supplied CCPA do-not-sell election. Seeded from
     * [AttriaxConfig.doNotSell]; updated by [setCcpaDoNotSell]. `null` → OMITTED; an
     * explicit `true`/`false` is EMITTED TOP-LEVEL as `doNotSell`. In-memory only,
     * matching [wrapperAttStatus]. See [ccpaDoNotSell] / [resolveDoNotSellWire].
     */
    @Volatile private var wrapperDoNotSell: Boolean? = config.doNotSell

    /**
     * Wrapper-supplied raw IAB US-Privacy string. Seeded from
     * [AttriaxConfig.usPrivacy]; updated by [setCcpaUsPrivacy]. `null`/blank → OMITTED;
     * a non-blank value is EMITTED TOP-LEVEL as `usPrivacy` (capped at 16 chars). See
     * [ccpaUsPrivacy] / [resolveUsPrivacyWire].
     */
    @Volatile private var wrapperUsPrivacy: String? = config.usPrivacy

    private val connectivityListener = ConnectivityMonitor.Listener { scheduleFlush() }

    /** Public tracking / revenue / identify surface. */
    val tracking: AttriaxTracking by lazy { AttriaxTracking(this) }

    /** Public GDPR consent + anonymous-mode surface. */
    val consent: AttriaxConsent by lazy { AttriaxConsent(this) }

    /** Public deep-link surface. */
    val deepLinks: AttriaxDeepLinks by lazy { AttriaxDeepLinks(this) }

    /** Public synchronization-state surface (observability). */
    val synchronization: AttriaxSynchronization by lazy { AttriaxSynchronization(this) }

    /** Public referrer-query surface. */
    val referrer: AttriaxReferrer by lazy { AttriaxReferrer(this) }

    /** Public SKAdNetwork surface. Apple-only; no-op off-iOS. */
    val skan: AttriaxSkan by lazy { AttriaxSkan(this) }

    /**
     * Pending resolve-response callbacks keyed by queued-request id. Registered
     * before enqueue; fired from [onRequestDelivered] with the
     * decoded response `data` map when the resolve is delivered. Guarded by
     * [pendingResolveLock] (replaces the JVM ConcurrentHashMap).
     */
    private val pendingResolveLock = SynchronizedObject()
    private val pendingResolveCallbacks = mutableMapOf<String, (Map<String, Any?>?) -> Unit>()

    /**
     * Coalesced periodic-flush timer (Flutter synchronizer
     * `_deferredFlushTimer`/`_scheduleDeferredFlush`). A single one-shot handle armed
     * when a NON-immediate request is enqueued (and not deferred by consent); it
     * drains the queue one `config.eventFlushIntervalMs` later. Never stacks — a
     * pending handle short-circuits re-arming, and an immediate [scheduleFlush]
     * cancels it. Guarded by [deferredFlushLock].
     */
    private val deferredFlushLock = SynchronizedObject()
    @Volatile private var deferredFlushHandle: AttriaxScheduler.ScheduledHandle? = null

    /** Deep-link runtime coordinator (dedup / initial-link probe / deferred). */
    private val deepLinkManager = AttriaxDeepLinkManager(
        nowMs = { clock.nowMs() },
        resolveDispatch = { uri, metadata, source, isInitialLink, onResolved ->
            dispatchDeepLinkResolve(uri, metadata, source, isInitialLink, onResolved)
        },
        readDeferredHandled = { store.getString(KEY_DEFERRED_DEEP_LINK_HANDLED) != null },
        writeDeferredHandled = { handled ->
            if (handled) store.putString(KEY_DEFERRED_DEEP_LINK_HANDLED, "true")
            else store.remove(KEY_DEFERRED_DEEP_LINK_HANDLED)
        },
        handleBrowserAction = { action -> maybeOpenBrowser(action) },
    )

    /**
     * Open a resolution's browser-fallback [action] when auto-handling is on
     * (Flutter `AttriaxDeepLinkBrowserHandler.handle`). Returns whether the SDK
     * opened it. Best-effort: a null action, the flag being off, or an opener failure
     * all yield `false` and never throw into the resolve callback.
     */
    private fun maybeOpenBrowser(action: AttriaxBrowserAction?): Boolean {
        if (action == null || !config.automaticBrowserHandling) return false
        // Only the REDACTED url reaches the log: warn is ungated, so these lines land in
        // logcat / the Apple unified log on RELEASE builds, and a resolved deep link can
        // carry campaign params, user ids, or one-time tokens. The exception MESSAGE is
        // dropped for the same reason — browser openers habitually embed the offending
        // URL in it (e.g. `IOException: Cannot run program ... https://...?token=...`),
        // which would smuggle the full link back into the log we just redacted.
        return try {
            browserOpener.open(action.url).also { opened ->
                if (!opened) {
                    logger.warn(
                        "Attriax could not open the resolved browser URL " +
                            "${attriaxRedactUrl(action.url)}.",
                    )
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "Attriax browser-open seam threw ${attriaxExceptionName(e)} for " +
                    "${attriaxRedactUrl(action.url)}.",
            )
            false
        }
    }

    /**
     * Backs the public `referrer` query surface:
     * reads the persisted raw/attribution install referrers and tracks the session /
     * latest deep-link referrers via the deep-link observer stream. Attached in
     * [init] before the app-open fires so a deferred referrer is captured live.
     */
    private val referrerCoordinator = AttriaxReferrerCoordinator(
        store = store,
        deepLinkManager = deepLinkManager,
    )

    val isInitialized: Boolean get() = initialized.value
    val isFirstLaunch: Boolean get() = firstLaunch
    val deviceId: String? get() = deviceIdentity?.value

    /**
     * SDK version + metadata snapshot (Flutter `Attriax.sdkSnapshot`,
     * attriax.dart:141). Built from the injected context snapshot (sdk api/package
     * version) and [AttriaxConfig.sdkMetadata]. Always available (the context is
     * captured at construction), unlike Flutter's null-until-init getter.
     */
    val sdkSnapshot: AttriaxSdkSnapshot
        get() = AttriaxSdkSnapshot(
            apiVersion = context.sdkApiVersion,
            packageVersion = context.sdkPackageVersion,
            metadata = config.sdkMetadata ?: emptyMap(),
        )

    var enabled: Boolean
        get() = enabledFlag.value
        set(value) {
            enabledFlag.value = value
            // Reflect a disable immediately (Flutter `deactivate` →
            // `disabled`); re-enable is resolved by the next flush.
            if (!value) syncState.set(AttriaxSynchronizationState.DISABLED)
        }

    /**
     * GDPR-safe anonymous-tracking toggle. The consent-driven
     * capture semantics are handled elsewhere; here it is a durable in-memory flag the
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

    // -------- synchronization state (observability) — behind `synchronization` --------

    internal val isSynchronized: Boolean get() = syncState.isSynchronized
    internal val synchronizationState: AttriaxSynchronizationState get() = syncState.state

    internal fun addSynchronizationStateListener(listener: AttriaxSynchronizationStateListener) =
        syncState.addListener(listener)

    internal fun removeSynchronizationStateListener(listener: AttriaxSynchronizationStateListener) =
        syncState.removeListener(listener)

    // -------- referrer query API — engine methods behind the `referrer` surface --------

    internal val originalInstallReferrer: AttriaxInstallReferrerDetails?
        get() = referrerCoordinator.originalInstallReferrer()
    internal val reinstallReferrer: AttriaxInstallReferrerDetails?
        get() = referrerCoordinator.reinstallReferrer()
    internal val rawInstallReferrer: String?
        get() = referrerCoordinator.rawInstallReferrer()
    internal val latestDeepLinkReferrer: AttriaxDeepLinkReferrerDetails?
        get() = referrerCoordinator.latestDeepLinkReferrer
    internal fun sessionReferrer(): AttriaxDeepLinkReferrerDetails? =
        referrerCoordinator.sessionReferrer()

    /**
     * Bootstrap the runtime (init sequence):
     *  1. restore persisted state,
     *  2. generate-or-load device id + resolve source,
     *  3. context snapshot is already captured (injected),
     *  4. mark isInitialized,
     *  5. schedule the app-open ONCE per runtime (best-effort, non-blocking).
     *
     * INVARIANT — `init()` MUST NOT BLOCK. It performs only bounded, LOCAL work
     * (persistence reads/writes, in-memory wiring) and then SCHEDULES everything
     * heavy off the calling thread. Nothing network, nothing that waits on a system
     * prompt, and no blocking I/O may run on the init thread: the app-open POST,
     * install-referrer fetch, attestation, ATT resolution, ASA capture and consent
     * sync are ALL dispatched to the background executor / scheduler. `init()` returns
     * fast; the async work — including ATT — may gate `/open`, but NEVER `init()`.
     * Any future addition here must preserve this: if it can block, schedule it.
     */
    fun init() {
        if (!initialized.compareAndSet(false, true)) {
            logger.warn("init() called more than once; ignoring the repeat call.")
            return
        }

        // An empty/blank project token silently makes the whole SDK inert (isEnabled()
        // is false, so every flush short-circuits). Say so ONCE at warn, unconditionally
        // — this is the single most common integration mistake and it used to present
        // as "the SDK does nothing and never explains why".
        if (config.normalizedProjectToken.isEmpty()) {
            logger.warn(
                "No project token configured: tracking is INERT and nothing will be sent " +
                    "to Attriax. Set AttriaxConfig.projectToken to your project's token.",
            )
        }

        firstLaunch = store.getString(KEY_FIRST_LAUNCH) == null
        deviceIdentity = deviceIdentityStore.loadOrCreate()
        // Restore persisted consent BEFORE any capture gating runs, and reconcile
        // the queue whenever the consent decision changes.
        consentManager.restore()
        consentManager.onStateChanged = { onConsentStateChanged() }
        connectivity.register(connectivityListener)

        // Subscribe the referrer coordinator to the deep-link stream BEFORE the
        // app-open fires, so a deferred deep-link referrer recovered from the
        // app-open response is captured as the session referrer (referrer).
        referrerCoordinator.attach()

        // Apple ATT-on-init is resolved INSIDE scheduleAppOpenIfNeeded, on the
        // background executor, BEFORE the open builds — so the resolved status still
        // rides the open WITHOUT blocking init() on the (blocking) ATT prompt. A
        // wrapper that drives ATT natively supplies the status via
        // AttriaxConfig.attStatus / consent.att.setStatus and leaves the flag off.
        scheduleAppOpenIfNeeded()

        // Apple Search Ads (AdServices) token capture — iOS-only,
        // best-effort, fault-isolated. Off the init thread; the fetch seam returns null
        // off-iOS so this never submits anything on the currently-built targets. Gated
        // by config + attribution consent (mirrors the Flutter reference).
        scheduleAsaTokenCaptureIfNeeded()

        // Session lifecycle: restore/continue-or-start the
        // session snapshot, seed the initial START / recovered END telemetry, and
        // begin foreground/background detection + the heartbeat timer. Gated on
        // sessionTrackingEnabled; identity is frozen at build like every other signal.
        bootstrapSession()

        // Flush any consent decision persisted with pendingSync across a restart.
        consentManager.flushPendingSync()

        // Automatic crash reporting: replay a crash a prior fatal handler
        // persisted (enqueue + one-shot clear), then install the OS uncaught-exception
        // handler. Both are gated by automaticCrashReportingEnabled; the native handler
        // is a compile-only placeholder until the platform chunk.
        crashReporting.replayPendingCrashReport()
        crashReporting.install()

        if (firstLaunch) {
            store.putString(KEY_FIRST_LAUNCH, "false")
        }

        // Deliberately NOT logged: the project token and the resolved device id. The
        // id's SOURCE is safe and is the useful diagnostic (it explains identity
        // fragmentation without printing the identifier itself).
        logger.info(
            "Initialized. appVersion=${config.appVersion} api=${config.apiBaseUrl} " +
                "firstLaunch=$firstLaunch deviceIdSource=${deviceIdentity?.source} " +
                "gdpr=${config.gdprEnabled} sessionTracking=${config.sessionTrackingEnabled}",
        )
    }

    /**
     * Restore-or-start the session at init and wire lifecycle telemetry.
     * A replaced session (continuation window exceeded on restore) is seeded as a
     * recovered END; a freshly-started session seeds the initial START
     * Then the lifecycle manager is activated (emits the seeded START +
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

    /** The current session snapshot (public surface), or null when none is active. */
    val currentSession: AttriaxSessionSnapshot? get() = sessionManager.currentSession

    /**
     * Enqueue an event (thin engine-level entry; the richer public tracking API
     * lives on [tracking]). Throws if called before [init].
     */
    fun recordEvent(name: String, eventData: Map<String, Any?>? = null, flushImmediately: Boolean = false) {
        requireInitialized()
        val identity = deviceIdentity
        // Stamp the current session: events carry the live session id +
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
     * First-launch eager-flush decision (Flutter tracking-manager
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
     * anon-capable signal). Identity is stamped from the frozen build-time
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
     * Session keep-alive injection hook for the dispatcher.
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
     * Consent-aware enqueue gate. Replaces the old
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
            if (!decision.capture) {
                logger.debug(
                    "Withholding ${request.kind}: the current consent decision does not " +
                        "allow capturing it.",
                )
                return false
            }
            val toEnqueue = if (decision.attachDeviceIdentity) {
                request
            } else {
                AttriaxConsentRequestRewrites.anonymize(request)
            }
            enqueue(toEnqueue)
            // Buffer locally (no flush) when network dispatch must be deferred by
            // consent; otherwise flush now (immediate) or arm the coalesced periodic
            // flush (non-immediate → drains after eventFlushIntervalMs).
            if (!decision.deferNetwork) {
                scheduleFlushOrDefer(flushImmediately)
            } else {
                // Consent defers network dispatch (deferred synchronization).
                logger.debug(
                    "Queued ${request.kind} locally: consent defers network dispatch until " +
                        "a decision is made.",
                )
                syncState.set(AttriaxSynchronizationState.DEFERRED)
            }
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
        if (!allowed) {
            logger.debug("Withholding ${request.kind}: attribution consent has not been granted.")
            return false
        }
        enqueue(request)
        scheduleFlushOrDefer(flushImmediately)
        return true
    }

    /**
     * Enqueue a pre-built request through the frozen-identity queue path and
     * optionally kick a flush. Shared by the [tracking] surface so
     * events/crashes/notifications/user updates all traverse the same engine.
     */
    internal fun enqueueRequest(
        request: AttriaxApiRequest,
        flushImmediately: Boolean,
    ) {
        requireInitialized()
        enqueueTracked(request, flushImmediately)
    }

    // -------- crash / error reporting — behind `tracking.recordError` + auto handler --------

    /**
     * Build the crash/error request (POST `/api/sdk/v1/crashes`) shared by the manual
     * [tracking] `recordError`, the public fatal-report path, and the automatic OS
     * uncaught-exception handler — so every crash uses the SAME frozen-identity wire
     * shape ([AttriaxRequestBuilders.buildCrash]). Identity is stamped in full here;
     * the consent gate at enqueue strips it when anonymous capture applies.
     */
    internal fun buildCrashRequest(
        error: Throwable,
        stackTrace: String?,
        fatal: Boolean,
        source: String,
        reason: String?,
        metadata: Map<String, Any?>?,
    ): AttriaxApiRequest = AttriaxRequestBuilders.buildCrash(
        projectToken = config.normalizedProjectToken,
        context = context,
        deviceId = deviceIdentity?.value,
        deviceIdSource = deviceIdentity?.source,
        source = AttriaxRevenue.trimOrNull(source) ?: "manual",
        isFatal = fatal,
        exceptionType = attriaxExceptionName(error),
        message = error.message ?: error.toString(),
        stackTrace = stackTrace ?: error.stackTraceToString(),
        isFirstLaunch = firstLaunch,
        clientOccurredAtIso = nowIso(),
        reason = AttriaxRevenue.trimOrNull(reason),
        sessionId = null,
        sessionRelativeTimeMs = null,
        metadata = metadata,
    )

    /**
     * Record an error/crash (Flutter `AttriaxTracking.recordError`).
     * `fatal = false` is a normal non-fatal enqueue (the existing behavior);
     * `fatal = true` PERSISTS the crash to durable storage ONLY (no immediate enqueue) —
     * it is delivered exclusively via replay on the next init, giving exactly-once
     * delivery through the durable queue. This is the path wrappers use to forward
     * framework-level fatal crashes.
     * Honors the engine `enabled` gate exactly like the previous surface method did.
     */
    internal fun recordError(
        error: Throwable,
        stackTrace: String?,
        fatal: Boolean,
        source: String,
        reason: String?,
        metadata: Map<String, Any?>?,
    ) {
        if (!isTrackingEnabled) return
        val request = buildCrashRequest(error, stackTrace, fatal, source, reason, metadata)
        if (fatal) {
            crashReporting.reportFatal(request)
        } else {
            enqueueRequest(request, flushImmediately = false)
        }
    }

    /**
     * Direct (non-queued) receipt validation. Works even when tracking
     * is disabled / consent is unresolved because it bypasses the queue and the
     * enabled gate entirely — it is a synchronous request/response, not a fire-and-
     * forget signal. Returns the typed [AttriaxRevenueReceiptValidationResult] parsed
     * from the decoded response (envelope already unwrapped by the transport), or
     * throws the transport exception on failure. Mirrors the Flutter reference
     * `Attriax.validateReceipt` (attriax.dart:188-202), which also returns the typed
     * result rather than a raw map.
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
    ): AttriaxRevenueReceiptValidationResult {
        requireInitialized()
        val normalizedReceipt = receipt.trim()
        require(normalizedReceipt.isNotEmpty()) { "receipt must not be empty." }
        val body = AttriaxRequestBuilders.buildReceiptValidate(
            projectToken = config.normalizedProjectToken,
            receipt = normalizedReceipt,
            deviceId = deviceIdentity?.value,
            clientOccurredAtIso = nowIso(),
            provider = AttriaxRevenue.trimOrNull(provider),
            environment = AttriaxRevenue.trimOrNull(environment),
            transactionId = AttriaxRevenue.trimOrNull(transactionId),
            productId = AttriaxRevenue.trimOrNull(productId),
            test = test,
        )
        val response = transport.post(
            AttriaxEndpoints.RECEIPTS_VALIDATE,
            Json.encode(body),
        )
        val decoded = response.body?.let { Json.decode(it) }
        return AttriaxRevenueReceiptValidationResult.fromResponse(decoded)
    }

    /** Best-effort flush kicked onto the background executor. */
    fun flush() {
        scheduleFlush()
    }

    // -------- deep links — engine methods behind `deepLinks` --------

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

    internal fun recordDeepLink(
        uri: String,
        metadata: Map<String, Any?>?,
        source: String,
    ): AttriaxDeepLinkEvent? {
        requireInitialized()
        return deepLinkManager.recordDeepLink(uri, metadata, source)
    }

    internal fun waitForDeepLinkResolution(rawEvent: AttriaxRawDeepLinkEvent): AttriaxDeepLinkEvent? {
        requireInitialized()
        return deepLinkManager.waitResolution(rawEvent)
    }

    /**
     * Create a short dynamic link. Sent DIRECTLY (non-queued) — it is a
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
            Json.encode(body),
        )
        val decoded = response.body?.let {
            Json.decode(it)
        } as? Map<*, *>
            ?: throw IllegalStateException("Attriax dynamic-link response was empty.")
        return parseDynamicLinkResult(decoded)
    }

    /**
     * Dispatch a deep-link resolve. Builds the DTO with the
     * consent-aware identity decision (deep-link diagnostics are anon-capable while
     * waiting), registers the resolution callback under the queued id, and enqueues
     * through the same consent gate + terminal-drop-exempt dispatcher as every other
     * request. When the resolve is delivered, [onRequestDelivered] fires the callback.
     */
    private fun dispatchDeepLinkResolve(
        uri: AttriaxUri,
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
            linkPath = AttriaxDeepLinkResolver.extractLinkPathFromUri(uri),
            sessionId = null,
            sessionRelativeTimeMs = null,
            metadata = metadata,
        )
        val id = AttriaxIdGenerator.generate()
        synchronized(pendingResolveLock) { pendingResolveCallbacks[id] = onResolved }
        queue.enqueue(
            AttriaxQueuedRequest(
                id = id,
                request = request,
                createdAtMs = clock.nowMs(),
            ),
        )
        // Deep-link resolve is anon-capable; flush unless network dispatch is deferred.
        if (!decision.deferNetwork) {
            scheduleFlush()
        } else {
            syncState.set(AttriaxSynchronizationState.DEFERRED)
        }
    }

    /**
     * Delivery callback from the dispatcher (single-send only). Routes app-open
     * responses to deferred deep-link recovery and resolve responses to
     * their pending resolution callback. Best-effort — never crash a flush.
     */
    private fun onRequestDelivered(
        queued: AttriaxQueuedRequest,
        response: HttpResponse,
    ) {
        try {
            when (queued.request.kind) {
                AttriaxApiRequest.KIND_OPEN -> {
                    val data = decodeResponseObject(response)
                    deepLinkManager.handleDeferredAppOpen(data)
                    // Persist the attribution records the app-open response returned so
                    // the referrer getters resolve real data (referrer).
                    referrerCoordinator.handleAppOpenResponse(data)
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
        response: HttpResponse,
    ): Map<String, Any?>? {
        val body = response.body ?: return null
        @Suppress("UNCHECKED_CAST")
        return Json.decode(body) as? Map<String, Any?>
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

    // -------- ATT — engine methods behind the `consent.att` surface --------

    /**
     * Resolved ATT status: the wrapper-supplied value if one is present, otherwise
     * the platform seam (the real `ATTrackingManager` status on Apple; UNKNOWN
     * off-Apple). Backs `consent.att.status`.
     */
    internal val attStatus: AttriaxAttStatus get() = wrapperAttStatus ?: attStatusProvider()

    /** Wrapper-supply setter for the natively-obtained ATT status. */
    internal fun setAttStatus(status: AttriaxAttStatus) {
        wrapperAttStatus = status
    }

    /**
     * Request ATT authorization via the seam, latching a resolved (non-UNKNOWN)
     * result as the wrapper-supplied status so a subsequent app-open / [attStatus]
     * read reports it. UNKNOWN (the off-iOS no-op result) is NOT latched, so it
     * never clobbers a real wrapper-supplied status.
     */
    internal fun requestAttAuthorization(timeoutMs: Long?): AttriaxAttStatus {
        val result = requestAttAuthorizationSeam(timeoutMs)
        if (result != AttriaxAttStatus.UNKNOWN) {
            wrapperAttStatus = result
        }
        return result
    }

    /**
     * The resolved ATT status as the wire string for the app-open, or `null` when it
     * should be OMITTED. UNKNOWN (non-Apple / unresolved) → omit; every real status
     * → its 1:1 wire value (mirrors the Flutter omit rule where the
     * non-applicable cases attach nothing).
     */
    internal fun resolveAttStatusWire(): String? =
        attStatus.takeIf { it != AttriaxAttStatus.UNKNOWN }?.wireValue

    // -------- CCPA — engine methods behind the `consent.ccpa` surface --------

    /** Current CCPA do-not-sell election (wrapper-supplied or config seed). Backs `consent.ccpa.doNotSell`. */
    internal val ccpaDoNotSell: Boolean? get() = wrapperDoNotSell

    /** Current raw IAB US-Privacy string (wrapper-supplied or config seed). Backs `consent.ccpa.usPrivacy`. */
    internal val ccpaUsPrivacy: String? get() = wrapperUsPrivacy

    /** Wrapper-supply setter for the CCPA do-not-sell election. */
    internal fun setCcpaDoNotSell(doNotSell: Boolean?) {
        wrapperDoNotSell = doNotSell
    }

    /** Wrapper-supply setter for the raw IAB US-Privacy string. */
    internal fun setCcpaUsPrivacy(usPrivacy: String?) {
        wrapperUsPrivacy = usPrivacy
    }

    /**
     * The resolved CCPA do-not-sell election for the app-open / identify (config seed
     * overridden by any runtime setter, mirroring [resolveAttStatusWire]). The builder
     * applies the omit-when-null rule; an explicit `true`/`false` is passed through (a
     * deliberate `false` may clear a prior server latch).
     */
    internal fun resolveDoNotSellWire(): Boolean? = wrapperDoNotSell

    /**
     * The resolved raw IAB US-Privacy string for the app-open / identify (config seed
     * overridden by any runtime setter). The builder applies the omit-when-null/blank
     * rule and the defensive 16-char cap (the api DTO's `@MaxLength(16)`).
     */
    internal fun resolveUsPrivacyWire(): String? = wrapperUsPrivacy

    // -------- SKAdNetwork — engine methods behind the `skan` surface --------

    /** Locally tracked SKAN state (passthrough subset), or null off-iOS. Backs `skan.state`. */
    internal val skanState: AttriaxSkanState? get() = skanEngine.currentState

    /**
     * Manual SKAN conversion-value update. Delegates to the pure engine,
     * which validates + applies the monotonic rules and pushes an advancing value to
     * StoreKit via the on-device seam. Backs `skan.updateConversionValue`.
     */
    internal fun updateSkanConversionValue(
        fineValue: Int,
        coarseValue: AttriaxSkanCoarseValue?,
        lockWindow: Boolean,
    ): AttriaxSkanUpdateResult =
        skanEngine.updateConversionValue(fineValue, coarseValue, lockWindow)

    /**
     * Best-effort pull of the project's configured SKAN conversion-value rules.
     * GETs `/api/sdk/v1/skan/conversion-config/<projectToken>` via the
     * shared [transport] and decodes the api `SdkCvConfigResponse` into
     * [AttriaxSkanConversionConfig]. Backs `skan.fetchConversionConfig`.
     *
     * Best-effort: a 404 (unknown token / no schema), any transport failure, or a
     * malformed payload returns null rather than throwing — the config pull must never
     * break the host. Performs blocking network I/O — call off the main thread.
     */
    internal fun fetchSkanConversionConfig(): AttriaxSkanConversionConfig? = try {
        val response = transport.get(
            "${AttriaxEndpoints.SKAN_CV_CONFIG}/${config.normalizedProjectToken}",
        )
        response.body?.let { AttriaxSkanCvConfigDecoder.decode(Json.decode(it)) }
    } catch (e: Exception) {
        // Unknown token (404), offline, or malformed payload — degrade to null.
        null
    }

    // -------- Apple Search Ads (AdServices) token capture --------

    /**
     * Wrapper-supply entrypoint: submit an Apple Search Ads (AdServices) attribution
     * token a host wrapper (Flutter / Unity / React Native iOS plugin) fetched natively
     * via `AAAttribution.attributionToken`. POSTs `{projectToken, token}` to
     * `/api/sdk/v1/asa/token` on ANY platform (the auto-capture seam only runs on iOS).
     * Best-effort: a blank token is ignored and any send failure is swallowed. Performs
     * blocking network I/O — call off the main thread.
     */
    fun submitAsaToken(token: String) = asaTokenManager.submit(token)

    /**
     * Build + POST the ASA token wire. Best-effort — the transport throws on non-2xx,
     * which the [asaTokenManager] catches (never breaking init/session).
     */
    private fun postAsaToken(token: String) {
        transport.post(
            AttriaxEndpoints.ASA_TOKEN,
            Json.encode(
                AttriaxRequestBuilders.buildAsaTokenBody(
                    projectToken = config.normalizedProjectToken,
                    token = token,
                ),
            ),
        )
    }

    /**
     * Fire the best-effort ASA token auto-capture once at init, gated by
     * [AttriaxConfig.asaTokenCaptureEnabled] AND attribution consent (mirrors Flutter's
     * `_allowsAttributionTracking` gate). Runs off the init thread on the flush executor
     * because it performs blocking network I/O. The fetch seam returns null off-iOS, so
     * this degrades to a silent no-op everywhere but iOS.
     */
    private fun scheduleAsaTokenCaptureIfNeeded() {
        if (!config.asaTokenCaptureEnabled) return
        if (!allowsAppOpenDispatch()) return
        if (flushExecutor.isShutdown) return
        flushExecutor.execute { asaTokenManager.captureAndReportIfNeeded() }
    }

    // -------- consent — engine methods behind the `consent.gdpr` surface --------

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
     * Request GDPR data erasure. Sends the deviceId to
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
            Json.encode(
                linkedMapOf<String, Any?>(
                    AttriaxApiRequest.FIELD_PROJECT_TOKEN to config.normalizedProjectToken,
                    AttriaxApiRequest.FIELD_DEVICE_ID to deviceId,
                ),
            ),
        )
        reset()
    }

    /**
     * Consent-resolution queue reconciliation. Runs the three
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

        // Attribution now denied → wipe stored install-attribution referrer state so
        // no install/reinstall attribution stays readable (privacy parity with the
        // Flutter reference `attriax_runtime.dart:976`, which calls
        // `prepareForDeniedAttributionState()` in the else branch of the active
        // runtime state when attribution is denied). The guard above already gates
        // this to GDPR-enabled + not-waiting, so it never fires prematurely.
        if (!consentManager.allowsAttributionTracking()) {
            referrerCoordinator.prepareForDeniedAttributionState()
        }
    }

    /** Clear SDK state to pre-init. */
    fun reset() {
        // Restore the previous OS uncaught-exception handler before wiping state.
        crashReporting.uninstall()
        // Tear down session telemetry BEFORE clearing identity so no in-flight
        // heartbeat/transition re-persists a snapshot after the wipe.
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
        // Back to pre-init (Flutter synchronizer `reset` → `initializing`).
        syncState.set(AttriaxSynchronizationState.INITIALIZING)
    }

    fun dispose() {
        crashReporting.uninstall()
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

        // Attestation and install-referrer capture are both
        // blocking I/O — they must NOT run on the init thread. When NEITHER needs the
        // network this launch, keep the synchronous fast path (re-attaching any cached
        // referrer) so there is zero behavior change for the common case. Otherwise
        // resolve both on the background executor before building the open.
        val referrerNeedsFetch = installReferrer.needsFetch()
        // Apple ATT-on-init: resolving the tracking-authorization status is BLOCKING
        // (it awaits the system prompt), so — like referrer/attestation capture — it
        // must run off the init thread and complete BEFORE the open builds so the
        // resolved status rides the open, WITHOUT blocking init().
        val attNeedsResolve = config.requestTrackingAuthorizationOnInit
        if (!attestationManager.isEnabled && !referrerNeedsFetch && !attNeedsResolve) {
            buildAndEnqueueAppOpen(attestation = null, referrer = installReferrer.cachedDetails())
            return
        }
        if (flushExecutor.isShutdown) return
        flushExecutor.execute {
            if (attNeedsResolve) {
                // Blocking ATT prompt — off the init thread; gates the open, not init().
                requestAttAuthorization(config.trackingAuthorizationStatusTimeoutMs)
            }
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
     * enqueue/hoist/flush semantics are identical.
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
            attStatus = resolveAttStatusWire(),
            doNotSell = resolveDoNotSellWire(),
            usPrivacy = resolveUsPrivacyWire(),
            sdkMetadata = config.sdkMetadata,
        )
        enqueue(open)
        // App-open carries attribution/install-referrer data (attribution-linked).
        // Enqueue always (so it is reconciled/hoisted later), but only flush it to
        // the network once attribution tracking is actually allowed — otherwise it
        // buffers until consent resolves.
        if (allowsAppOpenDispatch()) {
            scheduleFlush()
        } else {
            // App-open buffered pending attribution consent (deferred).
            syncState.set(AttriaxSynchronizationState.DEFERRED)
        }
    }

    /** Whether the app-open may be dispatched under the current consent state. */
    private fun allowsAppOpenDispatch(): Boolean =
        !config.gdprEnabled || consentManager.allowsAttributionTracking()

    private fun enqueue(request: AttriaxApiRequest) {
        queue.enqueue(
            AttriaxQueuedRequest(
                id = AttriaxIdGenerator.generate(),
                request = request,
                createdAtMs = clock.nowMs(),
            ),
        )
    }

    /**
     * Flush now when [flushImmediately], else arm the coalesced periodic flush
     * (Flutter synchronizer `enqueue`: `flushImmediately || interval==0`
     * → `scheduleFlush`, otherwise `_scheduleDeferredFlush`).
     */
    private fun scheduleFlushOrDefer(flushImmediately: Boolean) {
        if (flushImmediately) scheduleFlush() else scheduleDeferredFlush()
    }

    /**
     * Arm a single coalesced flush after `config.eventFlushIntervalMs` (
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
        // An immediate flush supersedes any pending coalesced flush (
        // Flutter `scheduleFlush` cancels `_deferredFlushTimer`).
        cancelDeferredFlush()
        if (!isEnabled()) {
            // Tracking disabled / project token empty (disabled synchronization).
            syncState.set(AttriaxSynchronizationState.DISABLED)
            logger.debug(
                "Flush skipped: tracking is disabled or no project token is set " +
                    "(queued requests stay on disk).",
            )
            return
        }
        if (flushExecutor.isShutdown) {
            logger.debug("Flush skipped: the SDK has been disposed.")
            return
        }
        flushExecutor.execute {
            // A flush has begun (Flutter synchronizer sets `synchronizing`
            // on enqueue / connectivity restore before draining the queue).
            syncState.set(AttriaxSynchronizationState.SYNCHRONIZING)
            terminalDropDuringFlush.value = false
            try {
                dispatcher.flush()
            } catch (e: Exception) {
                // Best-effort; a flush failure must never crash the host app. It must
                // not be INVISIBLE either — this catch swallowed every unexpected
                // engine fault without a trace.
                logger.error("Flush aborted by an unexpected ${attriaxExceptionName(e)}: ${e.message}")
            }
            resolveSynchronizationStateAfterFlush()
        }
    }

    /**
     * Resolve the terminal synchronization state after a flush pass (
     * Flutter synchronizer `_flushQueueAndRefreshSynchronization`, adapted to the
     * KMP dispatcher's structure):
     *
     *  * tracking disabled → [AttriaxSynchronizationState.DISABLED];
     *  * a request was permanently dropped this pass → [AttriaxSynchronizationState.FAILED];
     *  * the queue is empty → [AttriaxSynchronizationState.SYNCHRONIZED];
     *  * items remain and one carries a transport failure (`lastErrorClass`) →
     *    [AttriaxSynchronizationState.OFFLINE] (KMP has no push-based connectivity-lost
     *    signal, so a lost connection surfaces as a transport failure that leaves the
     *    queue non-empty);
     *  * items remain without a failure → [AttriaxSynchronizationState.DEFERRED].
     */
    private fun resolveSynchronizationStateAfterFlush() {
        if (!isEnabled()) {
            syncState.set(AttriaxSynchronizationState.DISABLED)
            return
        }
        if (terminalDropDuringFlush.value) {
            syncState.set(AttriaxSynchronizationState.FAILED)
            return
        }
        val pending = queue.readAll()
        val next = when {
            pending.isEmpty() -> AttriaxSynchronizationState.SYNCHRONIZED
            pending.any { it.lastErrorClass != null } -> AttriaxSynchronizationState.OFFLINE
            else -> AttriaxSynchronizationState.DEFERRED
        }
        // OFFLINE means real requests are stuck on disk — surface it at warn so it is
        // visible WITHOUT debug logs, since it is the state a customer needs to see.
        if (next == AttriaxSynchronizationState.OFFLINE) {
            logger.warn(
                "Cannot reach the Attriax API at ${config.apiBaseUrl}: ${pending.size} " +
                    "request(s) remain queued and will retry. Last error: " +
                    "${pending.firstOrNull { it.lastErrorClass != null }?.lastErrorClass}.",
            )
        } else {
            logger.debug("Synchronization state after flush: $next (${pending.size} queued).")
        }
        syncState.set(next)
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
        private val NOOP_SCHEDULER = object : AttriaxScheduler {
            override fun schedulePeriodic(
                intervalMs: Long,
                action: () -> Unit,
            ): AttriaxScheduler.ScheduledHandle =
                AttriaxScheduler.ScheduledHandle { }

            override fun scheduleOnce(
                delayMs: Long,
                action: () -> Unit,
            ): AttriaxScheduler.ScheduledHandle =
                AttriaxScheduler.ScheduledHandle { }
        }
    }
}
