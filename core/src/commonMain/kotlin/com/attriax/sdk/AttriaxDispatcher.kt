package com.attriax.sdk

import com.attriax.sdk.internal.consent.AttriaxConsentStateWire
import com.attriax.sdk.internal.consent.AttriaxGdprConsentValues

/*
 * ============================================================================
 *  Attriax KMP core — canonical command-dispatch table.
 * ============================================================================
 *
 *  The ONE `when (method)` that maps a dispatch key + a decoded argument [Map] onto a
 *  typed [Attriax] engine action, returning the result in a canonical JSON-friendly
 *  form ([AttriaxDispatchResult]). Every binding forwards here rather than owning its
 *  own parallel dispatch table:
 *
 *   - the C-ABI shared library (`AttriaxCApi.route`) parses `argsJson` → Map, calls
 *     [AttriaxDispatcher.execute], and JSON-encodes the result into its `{"ok":…}`
 *     envelope; and
 *   - the Android (JNI) / Apple (Obj-C over the XCFramework) wrappers call
 *     [AttriaxDispatcher.execute] directly and adapt the canonical result to their host
 *     types.
 *
 *  The dispatch KEYS are pinned by `AttriaxDispatchContract.METHODS`; the request/
 *  response wire shapes are pinned by the golden fixtures. This is the CALL direction
 *  only — event streams (synchronization-state / deep-link callbacks) are delivered
 *  out of band by each binding and are intentionally not modeled here.
 */

/**
 * The canonical outcome of an [AttriaxDispatcher.execute] call. Mirrors the C-ABI
 * envelope semantics without binding to any wire format:
 *  - [Ok] carries a JSON-friendly value (a primitive, `Map<String, Any?>`, `List`, or
 *    null) already shaped for uniform consumption;
 *  - [Err] carries a guard message (`missing:*`, `invalid:*`, host-error text); and
 *  - [Unimplemented] marks a key with no wired engine action.
 */
public sealed interface AttriaxDispatchResult {
    /** A successful call; [value] is a canonical primitive / Map / List / null. */
    public data class Ok(val value: Any?) : AttriaxDispatchResult

    /** A guarded failure (missing/invalid argument, host error). */
    public data class Err(val message: String) : AttriaxDispatchResult

    /** The method name is not wired to an engine action. */
    public data class Unimplemented(val method: String) : AttriaxDispatchResult
}

/**
 * The single canonical command-dispatch table over the [Attriax] engine. All bindings
 * (C-ABI, Android JNI, Apple Obj-C) forward to [execute]; there is no per-binding
 * `when`.
 */
public object AttriaxDispatcher {

    /**
     * Route [method] (with decoded [params]) to [engine] and return its canonical
     * result. Wired hot-path + broad-delegation methods reach the engine for real;
     * anything intentionally not wired returns [AttriaxDispatchResult.Unimplemented].
     * Reads arguments from [params] by key; result-shaping (snapshot / skan-state /
     * deep-link / referrer → Map) happens here so every binding consumes one form.
     */
    public fun execute(
        engine: Attriax,
        method: String,
        params: Map<String, Any?>,
    ): AttriaxDispatchResult {
        val args = params
        return when (method) {
            // -------- lifecycle --------
            "init" -> { engine.init(); ok(null) }
            "reset" -> { engine.reset(); ok(null) }
            "dispose" -> { engine.dispose(); ok(null) }
            "flush" -> { engine.flush(); ok(null) }

            // -------- engine state getters --------
            "getDeviceId" -> ok(engine.deviceId)
            "getIsFirstLaunch" -> ok(engine.isFirstLaunch)
            "getIsInitialized" -> ok(engine.isInitialized)
            "getIsSynchronized" -> ok(engine.synchronization.isSynchronized)
            "getSynchronizationState" -> ok(engine.synchronization.state.wire())
            "getSdkSnapshot" -> ok(sdkSnapshotMap(engine.sdkSnapshot))
            "getEnabled" -> ok(engine.enabled)
            "setEnabled" -> { engine.enabled = args.boolOr("enabled", true); ok(null) }
            "getAnonymousTracking" -> ok(engine.anonymousTrackingEnabled)
            "setAnonymousTracking" -> {
                engine.anonymousTrackingEnabled = args.boolOr("enabled", true)
                ok(null)
            }

            // -------- tracking / revenue --------
            "recordEvent" -> {
                val name = args.string("name") ?: return err("missing:name")
                engine.tracking.recordEvent(
                    name = name,
                    eventData = args.mapOrNull("eventData"),
                    flushImmediately = args.boolOr("flushImmediately", false),
                )
                ok(null)
            }
            "recordPageView" -> {
                val pageName = args.string("pageName") ?: return err("missing:pageName")
                engine.tracking.recordPageView(
                    pageName = pageName,
                    pageClass = args.string("pageClass"),
                    pageTitle = args.string("pageTitle"),
                    previousPageName = args.string("previousPageName"),
                    parameters = args.mapOrNull("parameters"),
                    source = args.stringOr("source", "manual"),
                    flushImmediately = args.boolOr("flushImmediately", false),
                )
                ok(null)
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
                ok(null)
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
                ok(null)
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
                ok(null)
            }
            "recordAdEvent" -> {
                val type = parseAdEventType(args.string("type"))
                    ?: return err("invalid:type")
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
                ok(null)
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
                ok(null)
            }

            // -------- notifications --------
            "recordNotification" -> {
                val type = parseNotificationType(args.string("type"))
                    ?: return err("invalid:type")
                val notificationId = args.string("notificationId")
                    ?: return err("missing:notificationId")
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
                ok(null)
            }

            // -------- identify / user --------
            "setUser" -> {
                engine.tracking.setUser(userId = args.string("userId"), userName = args.string("userName"))
                ok(null)
            }
            "setUserProperty" -> {
                val name = args.string("name") ?: return err("missing:name")
                engine.tracking.setUserProperty(name, args["value"])
                ok(null)
            }
            "setUserProperties" -> {
                engine.tracking.setUserProperties(args.mapOrNull("properties") ?: emptyMap())
                ok(null)
            }
            "clearUserProperties" -> {
                engine.tracking.clearUserProperties(args.stringListOrNull("propertyNames"))
                ok(null)
            }
            "registerFirebaseMessagingToken" -> {
                engine.tracking.registerFirebaseMessagingToken(args.string("token"), args.mapOrNull("metadata"))
                ok(null)
            }
            "registerApplePushToken" -> {
                engine.tracking.registerApplePushToken(args.string("token"), args.mapOrNull("metadata"))
                ok(null)
            }

            // -------- GDPR consent --------
            "setGdprConsent" -> {
                engine.consent.gdpr.setConsent(
                    analytics = args.boolOr("analytics", false),
                    attribution = args.boolOr("attribution", false),
                    adEvents = args.boolOr("adEvents", false),
                )
                ok(null)
            }
            "setGdprConsentNotRequired" -> { engine.consent.gdpr.setNotRequired(); ok(null) }
            "resetGdprConsent" -> { engine.consent.gdpr.reset(); ok(null) }
            "getGdprConsentState" -> ok(AttriaxConsentStateWire.toWire(engine.consent.gdpr.state))
            "getGdprConsentValues" -> ok(engine.consent.gdpr.values?.let { gdprValuesMap(it) })
            "getIsWaitingForGdprConsent" -> ok(engine.consent.gdpr.isWaitingForConsent)
            "needsGdprConsent" -> ok(engine.consent.gdpr.needsConsent(args.boolOr("localOnly", false)))
            "requestGdprDataErasure" -> { engine.consent.gdpr.requestDataErasure(); ok(null) }

            // -------- Apple ATT --------
            "getAttStatus" -> ok(engine.consent.att.status.wireValue)
            "setAttStatus" -> {
                val status = parseAttStatus(args.string("status"))
                    ?: return err("invalid:status")
                engine.consent.att.setStatus(status)
                ok(null)
            }
            "requestAttAuthorization" -> {
                val result = engine.consent.att.requestAuthorization(args.longOrNull("timeoutMs"))
                ok(result.wireValue)
            }

            // -------- CCPA --------
            "getDoNotSell" -> ok(engine.consent.ccpa.doNotSell)
            "setDoNotSell" -> {
                engine.consent.ccpa.setDoNotSell(args.boolOrNull("doNotSell"))
                ok(null)
            }
            "getUsPrivacy" -> ok(engine.consent.ccpa.usPrivacy)
            "setUsPrivacy" -> {
                engine.consent.ccpa.setUsPrivacy(args.string("usPrivacy"))
                ok(null)
            }
            "setCcpaConsent" -> {
                // Paired setter — CLEARS the omitted field (mirrors the wrappers'
                // ccpa.set), unlike the two individual setters above which leave the
                // other field untouched.
                engine.consent.ccpa.set(
                    doNotSell = args.boolOrNull("doNotSell"),
                    usPrivacy = args.string("usPrivacy"),
                )
                ok(null)
            }

            // -------- deep links --------
            "handleIncomingLink" -> {
                val uri = args.string("uri") ?: return err("missing:uri")
                engine.deepLinks.handleUri(uri, isInitialLink = args.boolOr("isInitialLink", false))
                ok(null)
            }
            "getLatestDeepLink" -> ok(engine.deepLinks.latestDeepLink?.let { deepLinkEventMap(it) })
            "getInitialDeepLink" -> ok(engine.deepLinks.initialDeepLink?.let { deepLinkEventMap(it) })
            "getRawInitialDeepLink" ->
                ok(engine.deepLinks.rawInitialDeepLink?.let { rawDeepLinkEventMap(it) })
            "getInitialDeepLinkResolved" -> ok(engine.deepLinks.initialDeepLinkResolved)
            "recordDeepLink" -> {
                val uri = args.string("uri") ?: return err("missing:uri")
                val event = engine.deepLinks.recordDeepLink(
                    uri = uri,
                    metadata = args.mapOrNull("metadata"),
                    source = args.stringOr("source", "manual"),
                )
                ok(event?.let { deepLinkEventMap(it) })
            }

            // -------- referrer --------
            "getOriginalInstallReferrer" ->
                ok(engine.referrer.getOriginalInstallReferrer()?.let { installReferrerMap(it) })
            "getReinstallReferrer" ->
                ok(engine.referrer.getReinstallReferrer()?.let { installReferrerMap(it) })
            "getRawInstallReferrer" -> ok(engine.referrer.getRawInstallReferrer())
            "getLatestDeepLinkReferrer" ->
                ok(engine.referrer.getLatestDeepLinkReferrer()?.let { deepLinkReferrerMap(it) })
            "getSessionReferrer" ->
                ok(engine.referrer.getSessionReferrer()?.let { deepLinkReferrerMap(it) })

            // -------- SKAdNetwork --------
            "getSkanState" -> ok(engine.skan.state?.let { skanStateMap(it) })
            "updateSkanConversionValue" -> {
                val result = engine.skan.updateConversionValue(
                    fineValue = args.intOr("fineValue", 0),
                    coarseValue = parseCoarseValue(args.string("coarseValue")),
                    lockWindow = args.boolOr("lockWindow", false),
                )
                ok(skanUpdateResultMap(result))
            }

            // -------- Apple Search Ads --------
            "submitAsaToken" -> {
                val token = args.string("token") ?: return err("missing:token")
                engine.submitAsaToken(token)
                ok(null)
            }

            // -------- receipt validation --------
            "validateReceipt" -> {
                val receipt = args.string("receipt") ?: return err("missing:receipt")
                val result = engine.validateReceipt(
                    receipt = receipt,
                    test = args.boolOr("test", false),
                    provider = args.string("provider"),
                    environment = args.string("environment"),
                    productId = args.string("productId"),
                    transactionId = args.string("transactionId"),
                )
                ok(receiptValidationMap(result))
            }

            else -> AttriaxDispatchResult.Unimplemented(method)
        }
    }

    private fun ok(value: Any?): AttriaxDispatchResult = AttriaxDispatchResult.Ok(value)
    private fun err(message: String): AttriaxDispatchResult = AttriaxDispatchResult.Err(message)
}

// ---------------------------------------------------------------------------
//  Result marshaling (engine values → JSON-friendly maps)
// ---------------------------------------------------------------------------

private fun sdkSnapshotMap(s: AttriaxSdkSnapshot): Map<String, Any?> = mapOf(
    "apiVersion" to s.apiVersion,
    "packageVersion" to s.packageVersion,
    "metadata" to s.metadata,
)

private fun gdprValuesMap(v: AttriaxGdprConsentValues): Map<String, Any?> = mapOf(
    "analytics" to v.analytics,
    "attribution" to v.attribution,
    "adEvents" to v.adEvents,
)

/**
 * Canonical deep-link event map. Also consumed by the C-ABI event-callback JSON, so it
 * stays `internal` rather than file-private.
 */
internal fun deepLinkEventMap(e: AttriaxDeepLinkEvent): Map<String, Any?> = mapOf(
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

// ---------------------------------------------------------------------------
//  Enum wire mappings (lowercased enum name unless a dedicated wireValue exists)
// ---------------------------------------------------------------------------

internal fun AttriaxSynchronizationState.wire(): String = name.lowercase()
private fun AttriaxDeepLinkTrigger.wire(): String = name.lowercase()
private fun AttriaxDeepLinkResolutionStatus.wire(): String = name.lowercase()
private fun AttriaxResolvedUrlOpenMode.wire(): String = name.lowercase()
private fun AttributionType.wire(): String = name.lowercase()
private fun AttriaxRevenueReceiptValidationStatus.wire(): String = name.lowercase()

internal fun parseAttStatus(raw: String?): AttriaxAttStatus? {
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
//  Argument helpers
// ---------------------------------------------------------------------------

/** Synthetic error carrier for `recordError` (host errors have no Kotlin type). */
internal class AttriaxHostError(message: String) : Throwable(message)

// -------- typed arg accessors over the decoded argument map --------

internal fun Map<String, Any?>.string(k: String): String? = this[k] as? String
internal fun Map<String, Any?>.stringOr(k: String, d: String): String = this[k] as? String ?: d
internal fun Map<String, Any?>.boolOr(k: String, d: Boolean): Boolean = this[k] as? Boolean ?: d
internal fun Map<String, Any?>.boolOrNull(k: String): Boolean? = this[k] as? Boolean

private fun Map<String, Any?>.numberOrNull(k: String): Double? = when (val v = this[k]) {
    is Long -> v.toDouble()
    is Int -> v.toDouble()
    is Double -> v
    is Number -> v.toDouble()
    else -> null
}

internal fun Map<String, Any?>.longOr(k: String, d: Long): Long = numberOrNull(k)?.toLong() ?: d
internal fun Map<String, Any?>.longOrNull(k: String): Long? = numberOrNull(k)?.toLong()
internal fun Map<String, Any?>.intOr(k: String, d: Int): Int = numberOrNull(k)?.toInt() ?: d
internal fun Map<String, Any?>.intOrNull(k: String): Int? = numberOrNull(k)?.toInt()
internal fun Map<String, Any?>.doubleOr(k: String, d: Double): Double = numberOrNull(k) ?: d
internal fun Map<String, Any?>.doubleOrNull(k: String): Double? = numberOrNull(k)

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.mapOrNull(k: String): Map<String, Any?>? = this[k] as? Map<String, Any?>

internal fun Map<String, Any?>.stringListOrNull(k: String): List<String>? =
    (this[k] as? List<*>)?.mapNotNull { it as? String }
