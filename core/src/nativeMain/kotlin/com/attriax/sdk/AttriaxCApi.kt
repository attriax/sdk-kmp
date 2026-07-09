@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.attriax.sdk

import com.attriax.sdk.internal.consent.AttriaxConsentStateWire
import com.attriax.sdk.internal.json.Json
import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString

/*
 * ============================================================================
 *  Attriax KMP core — C-ABI shared-library boundary (G1).
 * ============================================================================
 *
 *  A UNIFORM JSON-dispatch bridge over the [Attriax] engine, mirroring the JSON
 *  wrappers the Flutter platform interface and Unity `IAttriaxEnginePlatform`
 *  already speak. Desktop wrappers (Flutter FFI, Unity P/Invoke) load the produced
 *  `attriax_core.dll` / `libattriax_core.so` and drive the engine through five
 *  exported C functions (generated prototypes land in `libattriax_core_api.h`):
 *
 *    void*  attriax_create(const char* configJson, const char* dataDir);
 *    char*  attriax_dispatch(void* handle, const char* method, const char* argsJson);
 *    void   attriax_register_event_callback(void* handle,
 *                                           void (*callback)(const char* eventJson, void* userData),
 *                                           void* userData);
 *    void   attriax_free_string(char* ptr);
 *    void   attriax_destroy(void* handle);
 *
 *  MEMORY / OWNERSHIP:
 *   - The opaque `void* handle` is a [StableRef] to an [AttriaxNativeHandle] that
 *     carries the engine plus the registered event callback. Kotlin objects NEVER
 *     cross the boundary directly.
 *   - Every `char*` returned by `attriax_dispatch` is heap-allocated (nativeHeap)
 *     and MUST be released by the caller via `attriax_free_string`. Passing a
 *     pointer not produced here to `attriax_free_string` is undefined behavior.
 *   - `configJson`/`method`/`argsJson`/`eventJson` are NUL-terminated UTF-8. The
 *     `eventJson` handed to the C callback is valid ONLY for the duration of the
 *     call — the callback MUST copy it before returning; the wrapper frees nothing.
 *
 *  THREADING:
 *   - `attriax_dispatch` is synchronous (the native transport is `runBlocking`-
 *     bridged) and re-entrant across handles.
 *   - The registered C callback may be invoked on an ENGINE BACKGROUND THREAD (the
 *     flush executor for synchronization-state transitions, the resolution path for
 *     deep-link events). Marshaling back to a UI/main thread is the WRAPPER's
 *     responsibility.
 *
 *  SAFETY:
 *   - A null/garbage handle yields an `{"ok":false,...}` envelope (dispatch) or a
 *     no-op (destroy/register) rather than a crash, best-effort.
 *   - Every dispatch is wrapped so a Kotlin exception is converted to
 *     `{"ok":false,"error":"…"}` and NEVER unwinds across the C boundary.
 */

// ---------------------------------------------------------------------------
//  Per-handle state
// ---------------------------------------------------------------------------

/**
 * Everything one opaque handle owns: the [engine] and the (optional) registered C
 * event callback + its `userData`. StableRef-wrapped; resolved back on every
 * dispatch/destroy call.
 */
private class AttriaxNativeHandle(val engine: Attriax) {
    @Volatile
    var callback: CPointer<CFunction<(CPointer<ByteVar>?, COpaquePointer?) -> Unit>>? = null

    @Volatile
    var userData: COpaquePointer? = null

    @Volatile
    var alive: Boolean = true

    /**
     * Marshal [json] to a caller-transient C string and invoke the registered
     * callback. The string is freed once the callback returns (it may arrive on an
     * engine background thread — see the file header). No callback / dead handle →
     * a silent no-op. Never throws into the engine.
     */
    fun emit(json: String) {
        if (!alive) return
        val cb = callback ?: return
        val ptr = json.toCReturnString()
        try {
            cb.invoke(ptr, userData)
        } catch (e: Throwable) {
            // A misbehaving C callback must never crash a background engine thread.
        } finally {
            nativeHeap.free(ptr)
        }
    }
}

// ---------------------------------------------------------------------------
//  Exported C-ABI functions
// ---------------------------------------------------------------------------

/**
 * Build an engine from [configJson] (see [buildConfig]) persisting under [dataDir]
 * (empty/null → [AttriaxDesktopNative.defaultDataDir]) and return an opaque handle,
 * or `null` when construction fails (malformed config maps to defaults and does not
 * fail; a genuine engine-construction error yields null). Engine-side listeners are
 * attached here so callbacks route through the handle even before one is registered.
 */
@CName("attriax_create")
fun attriaxCreate(configJson: CPointer<ByteVar>?, dataDir: CPointer<ByteVar>?): COpaquePointer? {
    return try {
        val config = buildConfig(decodeObjectOrEmpty(configJson?.toKString()))
        val dir = dataDir?.toKString()?.takeIf { it.isNotBlank() }
            ?: AttriaxDesktopNative.defaultDataDir()
        val engine = AttriaxDesktopNative.create(config, dir)
        val handle = AttriaxNativeHandle(engine)
        // Route the two engine event streams through the handle callback (which may
        // be null until attriax_register_event_callback is called → the emit no-ops).
        engine.synchronization.addStateListener { state -> handle.emit(syncStateEventJson(state)) }
        engine.deepLinks.addListener { event -> handle.emit(deepLinkEventJson(event)) }
        StableRef.create(handle).asCPointer()
    } catch (e: Throwable) {
        null
    }
}

/**
 * Route [method] (with decoded [argsJson]) to the engine and return a heap-allocated
 * result-JSON envelope (`{"ok":true,"value":…}` / `{"ok":false,"error":"…"}`). The
 * caller frees it via [attriaxFreeString]. Guards a null handle, bad args JSON, and
 * any thrown exception into an error envelope — never crashes the boundary.
 */
@CName("attriax_dispatch")
fun attriaxDispatch(
    handle: COpaquePointer?,
    method: CPointer<ByteVar>?,
    argsJson: CPointer<ByteVar>?,
): CPointer<ByteVar>? {
    val envelope = try {
        val h = handle.resolveHandle()
            ?: return errEnvelope("invalid_handle").toCReturnString()
        val methodName = method?.toKString()
            ?: return errEnvelope("missing_method").toCReturnString()
        val args = try {
            decodeObjectOrEmpty(argsJson?.toKString())
        } catch (e: Throwable) {
            return errEnvelope("bad_args_json: ${e.message}").toCReturnString()
        }
        route(h, methodName, args)
    } catch (e: Throwable) {
        errEnvelope("${e::class.simpleName ?: "error"}: ${e.message}")
    }
    return envelope.toCReturnString()
}

/**
 * Register (or clear, with a null [callback]) the C event callback for [handle]. The
 * callback receives NUL-terminated UTF-8 `eventJson`; it may be invoked on an engine
 * background thread. No-op on an invalid handle.
 */
@CName("attriax_register_event_callback")
fun attriaxRegisterEventCallback(
    handle: COpaquePointer?,
    callback: CPointer<CFunction<(CPointer<ByteVar>?, COpaquePointer?) -> Unit>>?,
    userData: COpaquePointer?,
) {
    val h = handle.resolveHandle() ?: return
    h.callback = callback
    h.userData = userData
}

/**
 * Free a string previously returned by [attriaxDispatch]. Null-safe. The pointer
 * MUST have originated from this library (nativeHeap allocation).
 */
@CName("attriax_free_string")
fun attriaxFreeString(ptr: CPointer<ByteVar>?) {
    if (ptr != null) nativeHeap.free(ptr)
}

/**
 * Dispose the engine behind [handle] and release the [StableRef]. Idempotent and
 * null/garbage-safe. After this call the handle is invalid.
 */
@CName("attriax_destroy")
fun attriaxDestroy(handle: COpaquePointer?) {
    if (handle == null) return
    try {
        val ref = handle.asStableRef<AttriaxNativeHandle>()
        val h = ref.get()
        h.alive = false
        h.callback = null
        h.userData = null
        try {
            h.engine.dispose()
        } catch (e: Throwable) {
            // Best-effort dispose; still release the StableRef below.
        }
        ref.dispose()
    } catch (e: Throwable) {
        // Garbage/already-freed handle — no-op.
    }
}

// ---------------------------------------------------------------------------
//  Dispatch routing
// ---------------------------------------------------------------------------

/**
 * The uniform method router. Returns a result-JSON envelope STRING. Wired hot-path
 * + broad-delegation methods reach the engine for real; anything intentionally not
 * wired returns `{"ok":false,"error":"unimplemented:<method>"}`.
 */
private fun route(handle: AttriaxNativeHandle, method: String, args: Map<String, Any?>): String {
    val engine = handle.engine
    return when (method) {
        // -------- lifecycle --------
        "init" -> { engine.init(); okEnvelope(null) }
        "reset" -> { engine.reset(); okEnvelope(null) }
        "dispose" -> { engine.dispose(); okEnvelope(null) }
        "flush" -> { engine.flush(); okEnvelope(null) }

        // -------- engine state getters --------
        "getDeviceId" -> okEnvelope(engine.deviceId)
        "getIsFirstLaunch" -> okEnvelope(engine.isFirstLaunch)
        "getIsInitialized" -> okEnvelope(engine.isInitialized)
        "getIsSynchronized" -> okEnvelope(engine.synchronization.isSynchronized)
        "getSynchronizationState" -> okEnvelope(engine.synchronization.state.wire())
        "getSdkSnapshot" -> okEnvelope(sdkSnapshotMap(engine.sdkSnapshot))
        "getEnabled" -> okEnvelope(engine.enabled)
        "setEnabled" -> { engine.enabled = args.boolOr("enabled", true); okEnvelope(null) }
        "getAnonymousTracking" -> okEnvelope(engine.anonymousTrackingEnabled)
        "setAnonymousTracking" -> {
            engine.anonymousTrackingEnabled = args.boolOr("enabled", true)
            okEnvelope(null)
        }

        // -------- tracking / revenue --------
        "recordEvent" -> {
            val name = args.string("name") ?: return errEnvelope("missing:name")
            engine.tracking.recordEvent(
                name = name,
                eventData = args.mapOrNull("eventData"),
                flushImmediately = args.boolOr("flushImmediately", false),
            )
            okEnvelope(null)
        }
        "recordPageView" -> {
            val pageName = args.string("pageName") ?: return errEnvelope("missing:pageName")
            engine.tracking.recordPageView(
                pageName = pageName,
                pageClass = args.string("pageClass"),
                pageTitle = args.string("pageTitle"),
                previousPageName = args.string("previousPageName"),
                parameters = args.mapOrNull("parameters"),
                source = args.stringOr("source", "manual"),
                flushImmediately = args.boolOr("flushImmediately", false),
            )
            okEnvelope(null)
        }
        "recordPurchase" -> {
            engine.tracking.recordPurchase(
                revenue = args.doubleOr("revenue", 0.0),
                currency = args.stringOr("currency", "USD"),
                revenueInMicros = args.boolOr("revenueInMicros", false),
                purchaseType = args.string("purchaseType"),
                productId = args.string("productId"),
                transactionId = args.string("transactionId"),
                originalTransactionId = args.string("originalTransactionId"),
                validationProvider = args.string("validationProvider"),
                validationEnvironment = args.string("validationEnvironment"),
                purchaseToken = args.string("purchaseToken"),
                receiptData = args.string("receiptData"),
                signedPayload = args.string("signedPayload"),
                receiptSignature = args.string("receiptSignature"),
                isRenewal = args.boolOrNull("isRenewal"),
                quantity = args.intOr("quantity", 1),
                store = args.string("store"),
                packageName = args.string("packageName"),
                voided = args.boolOrNull("voided"),
                test = args.boolOrNull("test"),
                validationId = args.string("validationId"),
                metadata = args.mapOrNull("metadata"),
                flushImmediately = args.boolOr("flushImmediately", true),
            )
            okEnvelope(null)
        }
        "recordRefund" -> {
            engine.tracking.recordRefund(
                revenue = args.doubleOr("revenue", 0.0),
                currency = args.stringOr("currency", "USD"),
                revenueInMicros = args.boolOr("revenueInMicros", false),
                purchaseType = args.string("purchaseType"),
                productId = args.string("productId"),
                transactionId = args.string("transactionId"),
                originalTransactionId = args.string("originalTransactionId"),
                quantity = args.intOr("quantity", 1),
                store = args.string("store"),
                packageName = args.string("packageName"),
                voided = args.boolOrNull("voided"),
                test = args.boolOrNull("test"),
                reason = args.string("reason"),
                metadata = args.mapOrNull("metadata"),
                flushImmediately = args.boolOr("flushImmediately", true),
            )
            okEnvelope(null)
        }
        "recordAdRevenue" -> {
            engine.tracking.recordAdRevenue(
                revenue = args.doubleOr("revenue", 0.0),
                currency = args.stringOr("currency", "USD"),
                revenueInMicros = args.boolOr("revenueInMicros", false),
                adNetwork = args.string("adNetwork"),
                adFormat = args.string("adFormat"),
                adType = args.string("adType"),
                adPlacement = args.string("adPlacement"),
                test = args.boolOrNull("test"),
                metadata = args.mapOrNull("metadata"),
                flushImmediately = args.boolOr("flushImmediately", true),
            )
            okEnvelope(null)
        }
        "recordAdEvent" -> {
            val type = parseAdEventType(args.string("type"))
                ?: return errEnvelope("invalid:type")
            engine.tracking.recordAdEvent(
                type = type,
                adNetwork = args.string("adNetwork"),
                mediationNetwork = args.string("mediationNetwork"),
                adUnitId = args.string("adUnitId"),
                adPlacement = args.string("adPlacement"),
                adFormat = args.string("adFormat"),
                adType = args.string("adType"),
                failureReason = args.string("failureReason"),
                loadLatencyMs = args.doubleOrNull("loadLatencyMs"),
                rewardType = args.string("rewardType"),
                rewardAmount = args.doubleOrNull("rewardAmount"),
                test = args.boolOrNull("test"),
                metadata = args.mapOrNull("metadata"),
                flushImmediately = args.boolOr("flushImmediately", true),
            )
            okEnvelope(null)
        }
        "recordError" -> {
            val message = args.string("message") ?: args.string("errorMessage") ?: ""
            engine.tracking.recordError(
                error = AttriaxHostError(message),
                stackTrace = args.string("stackTrace"),
                fatal = args.boolOr("fatal", false),
                source = args.stringOr("source", "manual"),
                reason = args.string("reason"),
                metadata = args.mapOrNull("metadata"),
            )
            okEnvelope(null)
        }

        // -------- notifications --------
        "recordNotification" -> {
            val type = parseNotificationType(args.string("type"))
                ?: return errEnvelope("invalid:type")
            val notificationId = args.string("notificationId")
                ?: return errEnvelope("missing:notificationId")
            engine.tracking.recordNotification(
                type = type,
                notificationId = notificationId,
                linkId = args.string("linkId"),
                campaignId = args.string("campaignId"),
                title = args.string("title"),
                source = parseNotificationSource(args.string("source")),
                payload = args.mapOrNull("payload"),
                metadata = args.mapOrNull("metadata"),
                flushImmediately = args.boolOr("flushImmediately", false),
            )
            okEnvelope(null)
        }

        // -------- identify / user --------
        "setUser" -> {
            engine.tracking.setUser(userId = args.string("userId"), userName = args.string("userName"))
            okEnvelope(null)
        }
        "setUserProperty" -> {
            val name = args.string("name") ?: return errEnvelope("missing:name")
            engine.tracking.setUserProperty(name, args["value"])
            okEnvelope(null)
        }
        "setUserProperties" -> {
            engine.tracking.setUserProperties(args.mapOrNull("properties") ?: emptyMap())
            okEnvelope(null)
        }
        "clearUserProperties" -> {
            engine.tracking.clearUserProperties(args.stringListOrNull("propertyNames"))
            okEnvelope(null)
        }
        "registerFirebaseMessagingToken" -> {
            engine.tracking.registerFirebaseMessagingToken(args.string("token"), args.mapOrNull("metadata"))
            okEnvelope(null)
        }
        "registerApplePushToken" -> {
            engine.tracking.registerApplePushToken(args.string("token"), args.mapOrNull("metadata"))
            okEnvelope(null)
        }

        // -------- GDPR consent --------
        "setGdprConsent" -> {
            engine.consent.gdpr.setConsent(
                analytics = args.boolOr("analytics", false),
                attribution = args.boolOr("attribution", false),
                adEvents = args.boolOr("adEvents", false),
            )
            okEnvelope(null)
        }
        "setGdprConsentNotRequired" -> { engine.consent.gdpr.setNotRequired(); okEnvelope(null) }
        "resetGdprConsent" -> { engine.consent.gdpr.reset(); okEnvelope(null) }
        "getGdprConsentState" -> okEnvelope(AttriaxConsentStateWire.toWire(engine.consent.gdpr.state))
        "getGdprConsentValues" -> okEnvelope(engine.consent.gdpr.values?.let { gdprValuesMap(it) })
        "getIsWaitingForGdprConsent" -> okEnvelope(engine.consent.gdpr.isWaitingForConsent)
        "needsGdprConsent" -> okEnvelope(engine.consent.gdpr.needsConsent(args.boolOr("localOnly", false)))
        "requestGdprDataErasure" -> { engine.consent.gdpr.requestDataErasure(); okEnvelope(null) }

        // -------- Apple ATT --------
        "getAttStatus" -> okEnvelope(engine.consent.att.status.wireValue)
        "setAttStatus" -> {
            val status = parseAttStatus(args.string("status"))
                ?: return errEnvelope("invalid:status")
            engine.consent.att.setStatus(status)
            okEnvelope(null)
        }
        "requestAttAuthorization" -> {
            val result = engine.consent.att.requestAuthorization(args.longOrNull("timeoutMs"))
            okEnvelope(result.wireValue)
        }

        // -------- CCPA (Epic 10.1) --------
        "getDoNotSell" -> okEnvelope(engine.consent.ccpa.doNotSell)
        "setDoNotSell" -> {
            engine.consent.ccpa.setDoNotSell(args.boolOrNull("doNotSell"))
            okEnvelope(null)
        }
        "getUsPrivacy" -> okEnvelope(engine.consent.ccpa.usPrivacy)
        "setUsPrivacy" -> {
            engine.consent.ccpa.setUsPrivacy(args.string("usPrivacy"))
            okEnvelope(null)
        }

        // -------- deep links --------
        "handleIncomingLink" -> {
            val uri = args.string("uri") ?: return errEnvelope("missing:uri")
            engine.deepLinks.handleUri(uri, isInitialLink = args.boolOr("isInitialLink", false))
            okEnvelope(null)
        }
        "getLatestDeepLink" -> okEnvelope(engine.deepLinks.latestDeepLink?.let { deepLinkEventMap(it) })
        "getInitialDeepLink" -> okEnvelope(engine.deepLinks.initialDeepLink?.let { deepLinkEventMap(it) })
        "getRawInitialDeepLink" ->
            okEnvelope(engine.deepLinks.rawInitialDeepLink?.let { rawDeepLinkEventMap(it) })
        "getInitialDeepLinkResolved" -> okEnvelope(engine.deepLinks.initialDeepLinkResolved)
        "recordDeepLink" -> {
            val uri = args.string("uri") ?: return errEnvelope("missing:uri")
            val event = engine.deepLinks.recordDeepLink(
                uri = uri,
                metadata = args.mapOrNull("metadata"),
                source = args.stringOr("source", "manual"),
            )
            okEnvelope(event?.let { deepLinkEventMap(it) })
        }

        // -------- referrer --------
        "getOriginalInstallReferrer" ->
            okEnvelope(engine.referrer.getOriginalInstallReferrer()?.let { installReferrerMap(it) })
        "getReinstallReferrer" ->
            okEnvelope(engine.referrer.getReinstallReferrer()?.let { installReferrerMap(it) })
        "getRawInstallReferrer" -> okEnvelope(engine.referrer.getRawInstallReferrer())
        "getLatestDeepLinkReferrer" ->
            okEnvelope(engine.referrer.getLatestDeepLinkReferrer()?.let { deepLinkReferrerMap(it) })
        "getSessionReferrer" ->
            okEnvelope(engine.referrer.getSessionReferrer()?.let { deepLinkReferrerMap(it) })

        // -------- SKAdNetwork --------
        "getSkanState" -> okEnvelope(engine.skan.state?.let { skanStateMap(it) })
        "updateSkanConversionValue" -> {
            val result = engine.skan.updateConversionValue(
                fineValue = args.intOr("fineValue", 0),
                coarseValue = parseCoarseValue(args.string("coarseValue")),
                lockWindow = args.boolOr("lockWindow", false),
            )
            okEnvelope(skanUpdateResultMap(result))
        }

        // -------- Apple Search Ads --------
        "submitAsaToken" -> {
            val token = args.string("token") ?: return errEnvelope("missing:token")
            engine.submitAsaToken(token)
            okEnvelope(null)
        }

        // -------- receipt validation --------
        "validateReceipt" -> {
            val receipt = args.string("receipt") ?: return errEnvelope("missing:receipt")
            val result = engine.validateReceipt(
                receipt = receipt,
                test = args.boolOr("test", false),
                provider = args.string("provider"),
                environment = args.string("environment"),
                productId = args.string("productId"),
                transactionId = args.string("transactionId"),
            )
            okEnvelope(receiptValidationMap(result))
        }

        else -> errEnvelope("unimplemented:$method")
    }
}

// ---------------------------------------------------------------------------
//  Config construction from JSON
// ---------------------------------------------------------------------------

/**
 * Build an [AttriaxConfig] from the decoded config map. Every unknown/missing field
 * falls back to the [AttriaxConfig] default. The object-seam `attestationProvider`
 * is intentionally NOT constructable from JSON (it stays null → no attestation).
 */
private fun buildConfig(j: Map<String, Any?>): AttriaxConfig = AttriaxConfig(
    projectToken = j.stringOr("projectToken", ""),
    apiBaseUrl = j.string("apiBaseUrl") ?: AttriaxConfig.DEFAULT_API_BASE_URL,
    appVersion = j.string("appVersion"),
    appBuildNumber = j.string("appBuildNumber"),
    appPackageName = j.string("appPackageName"),
    sdkMetadata = j.mapOrNull("sdkMetadata"),
    deviceContext = buildDeviceContext(j.mapOrNull("deviceContext")),
    enableDebugLogs = j.boolOr("enableDebugLogs", false),
    requestTimeoutMs = j.longOr("requestTimeoutMs", 12_000L),
    maxQueueSize = j.intOr("maxQueueSize", 500).let { if (it > 0) it else 500 },
    eventFlushIntervalMs = j.longOr("eventFlushIntervalMs", 60_000L),
    flushEventsImmediatelyOnFirstLaunch = j.boolOr("flushEventsImmediatelyOnFirstLaunch", true),
    collectAdvertisingId = j.boolOr("collectAdvertisingId", true),
    automaticCrashReportingEnabled = j.boolOr("automaticCrashReportingEnabled", true),
    gdprEnabled = j.boolOr("gdprEnabled", false),
    anonymousTracking = j.boolOr("anonymousTracking", true),
    sessionTrackingEnabled = j.boolOr("sessionTrackingEnabled", true),
    sessionHeartbeatIntervalMs = j.longOr("sessionHeartbeatIntervalMs", 5 * 60_000L),
    firstLaunchSessionHeartbeatIntervalMs = j.longOr("firstLaunchSessionHeartbeatIntervalMs", 30_000L),
    installReferrerEnabled = j.boolOr("installReferrerEnabled", true),
    attestationEnabled = j.boolOr("attestationEnabled", false),
    pinnedCertificateSha256Fingerprints = j.stringListOrNull("pinnedCertificateSha256Fingerprints") ?: emptyList(),
    automaticBrowserHandling = j.boolOr("automaticBrowserHandling", true),
    attStatus = parseAttStatus(j.string("attStatus")),
    requestTrackingAuthorizationOnInit = j.boolOr("requestTrackingAuthorizationOnInit", false),
    trackingAuthorizationStatusTimeoutMs = j.longOr("trackingAuthorizationStatusTimeoutMs", 60_000L),
    skan = j.mapOrNull("skan")?.let { s ->
        AttriaxSkanConfig(
            enabled = s.boolOr("enabled", true),
            registerFirstLaunchValue = s.boolOr("registerFirstLaunchValue", true),
        )
    },
    asaTokenCaptureEnabled = j.boolOr("asaTokenCaptureEnabled", true),
    doNotSell = j.boolOrNull("doNotSell"),
    usPrivacy = j.string("usPrivacy"),
)

/**
 * Build the optional wrapper-supplied [AttriaxDeviceContext]. The five model/
 * manufacturer/osVersion/timezone/language fields are REQUIRED by the type; when the
 * block is present but missing any of them we return null (no partial context)
 * rather than throwing.
 */
private fun buildDeviceContext(d: Map<String, Any?>?): AttriaxDeviceContext? {
    if (d == null) return null
    val model = d.string("model") ?: return null
    val manufacturer = d.string("manufacturer") ?: return null
    val osVersion = d.string("osVersion") ?: return null
    val timezone = d.string("timezone") ?: return null
    val language = d.string("language") ?: return null
    return AttriaxDeviceContext(
        model = model,
        manufacturer = manufacturer,
        osVersion = osVersion,
        timezone = timezone,
        language = language,
        brand = d.string("brand"),
        hardware = d.string("hardware"),
        name = d.string("name"),
        isPhysicalDevice = d.boolOrNull("isPhysicalDevice"),
        screenWidth = d.intOrNull("screenWidth"),
        screenHeight = d.intOrNull("screenHeight"),
        screenResolution = d.string("screenResolution"),
        devicePixelRatio = d.doubleOrNull("devicePixelRatio"),
        colorDepth = d.intOrNull("colorDepth"),
        supportedAbis = d.stringListOrNull("supportedAbis"),
        metadata = d.mapOrNull("metadata"),
        advertisingId = d.string("advertisingId"),
        androidId = d.string("androidId"),
    )
}

// ---------------------------------------------------------------------------
//  Result marshaling (engine values → JSON-friendly maps)
// ---------------------------------------------------------------------------

private fun sdkSnapshotMap(s: AttriaxSdkSnapshot): Map<String, Any?> = mapOf(
    "apiVersion" to s.apiVersion,
    "packageVersion" to s.packageVersion,
    "metadata" to s.metadata,
)

private fun gdprValuesMap(v: com.attriax.sdk.internal.consent.AttriaxGdprConsentValues): Map<String, Any?> = mapOf(
    "analytics" to v.analytics,
    "attribution" to v.attribution,
    "adEvents" to v.adEvents,
)

private fun deepLinkEventMap(e: AttriaxDeepLinkEvent): Map<String, Any?> = mapOf(
    "uri" to e.uri.raw,
    "clickedAtMs" to e.clickedAtMs,
    "consumedAtMs" to e.consumedAtMs,
    "found" to e.found,
    "trigger" to e.trigger.wire(),
    "isAttriaxSubDomain" to e.isAttriaxSubDomain,
    "status" to e.status.wire(),
    "data" to e.data,
    "utm" to e.utm,
    "browserAction" to e.browserAction?.let { browserActionMap(it) },
    "handledBySdk" to e.handledBySdk,
)

private fun rawDeepLinkEventMap(e: AttriaxRawDeepLinkEvent): Map<String, Any?> = mapOf(
    "uri" to e.uri.raw,
    "receivedAtMs" to e.receivedAtMs,
    "isInitial" to e.isInitial,
)

private fun browserActionMap(a: AttriaxBrowserAction): Map<String, Any?> = mapOf(
    "url" to a.url,
    "openMode" to a.openMode.wire(),
)

private fun installReferrerMap(r: AttriaxInstallReferrerDetails): Map<String, Any?> = mapOf(
    "rawPlatformInstallReferrer" to r.rawPlatformInstallReferrer,
    "source" to r.source,
    "medium" to r.medium,
    "campaign" to r.campaign,
    "term" to r.term,
    "content" to r.content,
    "adNetwork" to r.adNetwork,
    "adClickId" to r.adClickId,
    "attributionType" to r.attributionType.wire(),
    "deepLinkUrl" to r.deepLinkUrl,
    "deepLinkData" to r.deepLinkData,
    "registeredAt" to r.registeredAt,
    "installBeginTimestampSeconds" to r.installBeginTimestampSeconds,
    "referrerClickTimestampSeconds" to r.referrerClickTimestampSeconds,
    "googlePlayInstantParam" to r.googlePlayInstantParam,
    "precision" to r.precision,
    "utm" to r.utm,
)

private fun deepLinkReferrerMap(r: AttriaxDeepLinkReferrerDetails): Map<String, Any?> = mapOf(
    "uri" to r.uri.raw,
    "receivedAtMs" to r.receivedAtMs,
    "clickedAtMs" to r.clickedAtMs,
    "consumedAtMs" to r.consumedAtMs,
    "trigger" to r.trigger.wire(),
    "isAttriaxDomain" to r.isAttriaxDomain,
    "found" to r.found,
    "data" to r.data,
    "utm" to r.utm,
    "browserAction" to r.browserAction?.let { browserActionMap(it) },
)

private fun skanStateMap(s: AttriaxSkanState): Map<String, Any?> = mapOf(
    "enabled" to s.enabled,
    "fineValue" to s.fineValue,
    "coarseValue" to s.coarseValue?.wireValue,
    "lockWindow" to s.lockWindow,
)

private fun skanUpdateResultMap(r: AttriaxSkanUpdateResult): Map<String, Any?> = mapOf(
    "status" to r.status.wireValue,
    "message" to r.message,
    "fineValue" to r.fineValue,
    "coarseValue" to r.coarseValue?.wireValue,
    "lockWindow" to r.lockWindow,
)

private fun receiptValidationMap(r: AttriaxRevenueReceiptValidationResult): Map<String, Any?> = mapOf(
    "validationId" to r.validationId,
    "status" to r.status.wire(),
    "publicReceipt" to r.publicReceipt,
    "requestVersion" to r.requestVersion,
    "acceptedAtMs" to r.acceptedAtMs,
    "provider" to r.provider,
    "environment" to r.environment,
    "transactionId" to r.transactionId,
    "originalTransactionId" to r.originalTransactionId,
    "productId" to r.productId,
    "failureReason" to r.failureReason,
    "expiresAtMs" to r.expiresAtMs,
    "providerResult" to r.providerResult,
)

// -------- event stream JSON (for the C callback) --------

private fun syncStateEventJson(state: AttriaxSynchronizationState): String =
    Json.encode(mapOf("type" to "synchronizationState", "state" to state.wire()))

private fun deepLinkEventJson(event: AttriaxDeepLinkEvent): String =
    Json.encode(mapOf("type" to "deepLink", "event" to deepLinkEventMap(event)))

// ---------------------------------------------------------------------------
//  Enum wire mappings (lowercased enum name unless a dedicated wireValue exists)
// ---------------------------------------------------------------------------

private fun AttriaxSynchronizationState.wire(): String = name.lowercase()
private fun AttriaxDeepLinkTrigger.wire(): String = name.lowercase()
private fun AttriaxDeepLinkResolutionStatus.wire(): String = name.lowercase()
private fun AttriaxResolvedUrlOpenMode.wire(): String = name.lowercase()
private fun AttributionType.wire(): String = name.lowercase()
private fun AttriaxRevenueReceiptValidationStatus.wire(): String = name.lowercase()

private fun parseAttStatus(raw: String?): AttriaxAttStatus? {
    val v = raw?.trim() ?: return null
    return AttriaxAttStatus.entries.firstOrNull { it.wireValue == v }
}

private fun parseCoarseValue(raw: String?): AttriaxSkanCoarseValue? {
    val v = raw?.trim() ?: return null
    return AttriaxSkanCoarseValue.entries.firstOrNull { it.wireValue == v }
}

private fun parseAdEventType(raw: String?): AttriaxAdEventType? {
    val v = raw?.trim() ?: return null
    return AttriaxAdEventType.entries.firstOrNull {
        it.name.equals(v, ignoreCase = true) || it.eventName.equals(v, ignoreCase = true)
    }
}

private fun parseNotificationType(raw: String?): AttriaxNotificationEventType? {
    val v = raw?.trim() ?: return null
    return AttriaxNotificationEventType.entries.firstOrNull {
        it.name.equals(v, ignoreCase = true) || it.wireValue.equals(v, ignoreCase = true)
    }
}

private fun parseNotificationSource(raw: String?): AttriaxNotificationEventSource? {
    val v = raw?.trim() ?: return null
    return AttriaxNotificationEventSource.entries.firstOrNull {
        it.name.equals(v, ignoreCase = true) || it.wireValue.equals(v, ignoreCase = true)
    }
}

// ---------------------------------------------------------------------------
//  JSON / C-string helpers
// ---------------------------------------------------------------------------

/** Synthetic error carrier for [route]'s `recordError` (host errors have no Kotlin type). */
private class AttriaxHostError(message: String) : Throwable(message)

private fun okEnvelope(value: Any?): String = Json.encode(mapOf("ok" to true, "value" to value))

private fun errEnvelope(message: String): String = Json.encode(mapOf("ok" to false, "error" to message))

private fun decodeObjectOrEmpty(text: String?): Map<String, Any?> {
    if (text.isNullOrBlank()) return emptyMap()
    @Suppress("UNCHECKED_CAST")
    return (Json.decode(text) as? Map<String, Any?>) ?: emptyMap()
}

/** Resolve an opaque handle back to its [AttriaxNativeHandle], or null on garbage/null. */
private fun COpaquePointer?.resolveHandle(): AttriaxNativeHandle? {
    if (this == null) return null
    return try {
        this.asStableRef<AttriaxNativeHandle>().get()
    } catch (e: Throwable) {
        null
    }
}

/**
 * Copy this string into a fresh NUL-terminated UTF-8 buffer on the native heap. The
 * caller (or [emit]) owns it and frees it via [attriaxFreeString] / `nativeHeap.free`.
 */
private fun String.toCReturnString(): CPointer<ByteVar> {
    val bytes = this.encodeToByteArray()
    val ptr = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
    for (i in bytes.indices) ptr[i] = bytes[i]
    ptr[bytes.size] = 0
    return ptr
}

// -------- typed arg accessors over the decoded JSON map --------

private fun Map<String, Any?>.string(k: String): String? = this[k] as? String
private fun Map<String, Any?>.stringOr(k: String, d: String): String = this[k] as? String ?: d
private fun Map<String, Any?>.boolOr(k: String, d: Boolean): Boolean = this[k] as? Boolean ?: d
private fun Map<String, Any?>.boolOrNull(k: String): Boolean? = this[k] as? Boolean

private fun Map<String, Any?>.numberOrNull(k: String): Double? = when (val v = this[k]) {
    is Long -> v.toDouble()
    is Int -> v.toDouble()
    is Double -> v
    is Number -> v.toDouble()
    else -> null
}

private fun Map<String, Any?>.longOr(k: String, d: Long): Long = numberOrNull(k)?.toLong() ?: d
private fun Map<String, Any?>.longOrNull(k: String): Long? = numberOrNull(k)?.toLong()
private fun Map<String, Any?>.intOr(k: String, d: Int): Int = numberOrNull(k)?.toInt() ?: d
private fun Map<String, Any?>.intOrNull(k: String): Int? = numberOrNull(k)?.toInt()
private fun Map<String, Any?>.doubleOr(k: String, d: Double): Double = numberOrNull(k) ?: d
private fun Map<String, Any?>.doubleOrNull(k: String): Double? = numberOrNull(k)

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.mapOrNull(k: String): Map<String, Any?>? = this[k] as? Map<String, Any?>

private fun Map<String, Any?>.stringListOrNull(k: String): List<String>? =
    (this[k] as? List<*>)?.mapNotNull { it as? String }
