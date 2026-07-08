package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxRevenue
import com.attriax.sdk.internal.request.AttriaxRequestBuilders

/**
 * Public tracking, revenue, notification, error, and identify surface
 * (PARITY §4, rows E1–E6). Mirrors the Flutter reference `AttriaxTracking`.
 *
 * Every standardized helper (`recordPurchase`/`recordRefund`/`recordAdRevenue`/
 * `recordAdEvent`/`recordPageView`) LOWERS to [recordEvent] with the reserved
 * event names + param keys — there is no separate revenue endpoint for tracked
 * purchases. Requests traverse the same frozen-identity queue + dispatcher as the
 * engine; only [Attriax.validateReceipt] bypasses the queue.
 *
 * Consent-driven anonymous capture and session stamping arrive in later slices;
 * this surface honors the engine `enabled` flag and stamps the frozen build-time
 * identity the engine already resolved.
 */
class AttriaxTracking internal constructor(private val engine: Attriax) {

    /** Whether event-style tracking is currently enabled (delegates to the engine). */
    var enabled: Boolean
        get() = engine.enabled
        set(value) { engine.enabled = value }

    /** Whether GDPR-safe anonymous tracking is allowed (delegates to the engine). */
    var anonymousTrackingEnabled: Boolean
        get() = engine.anonymousTrackingEnabled
        set(value) { engine.anonymousTrackingEnabled = value }

    // -------- events / page views --------

    fun recordEvent(
        name: String,
        eventData: Map<String, Any?>? = null,
        flushImmediately: Boolean = false,
    ) {
        if (!engine.isTrackingEnabled) return
        engine.recordEvent(name, eventData = eventData, flushImmediately = flushImmediately)
    }

    fun recordPageView(
        pageName: String,
        pageClass: String? = null,
        pageTitle: String? = null,
        previousPageName: String? = null,
        parameters: Map<String, Any?>? = null,
        source: String = "manual",
        flushImmediately: Boolean = false,
    ) {
        val normalizedPageName = pageName.trim()
        require(normalizedPageName.isNotEmpty()) { "pageName must not be empty." }

        val eventData = LinkedHashMap<String, Any?>()
        parameters?.let { eventData.putAll(it) }
        eventData[AttriaxAnalyticsParamKeys.PAGE_NAME] = normalizedPageName
        AttriaxRevenue.trimOrNull(pageClass)?.let { eventData[AttriaxAnalyticsParamKeys.PAGE_CLASS] = it }
        AttriaxRevenue.trimOrNull(pageTitle)?.let { eventData[AttriaxAnalyticsParamKeys.PAGE_TITLE] = it }
        AttriaxRevenue.trimOrNull(previousPageName)
            ?.let { eventData[AttriaxAnalyticsParamKeys.PREVIOUS_PAGE_NAME] = it }
        eventData[AttriaxAnalyticsParamKeys.SOURCE] = source

        recordEvent(
            AttriaxAnalyticsEventKeys.PAGE_VIEW,
            eventData = eventData,
            flushImmediately = flushImmediately,
        )
    }

    // -------- revenue (lowered to recordEvent; rows E1/E2/E3) --------

    /**
     * Record a completed purchase (row E1). Flushes immediately by default.
     * Currency is validated `^[A-Z]{3}$` else revenue is forced to `0 USD` (row E3).
     */
    fun recordPurchase(
        revenue: Double,
        currency: String = "USD",
        revenueInMicros: Boolean = false,
        purchaseType: String? = null,
        productId: String? = null,
        transactionId: String? = null,
        originalTransactionId: String? = null,
        validationProvider: String? = null,
        validationEnvironment: String? = null,
        purchaseToken: String? = null,
        receiptData: String? = null,
        signedPayload: String? = null,
        receiptSignature: String? = null,
        isRenewal: Boolean? = null,
        quantity: Int = 1,
        store: String? = null,
        packageName: String? = null,
        voided: Boolean? = null,
        test: Boolean? = null,
        validationId: String? = null,
        metadata: Map<String, Any?>? = null,
        flushImmediately: Boolean = true,
    ) {
        require(revenue.isFinite()) { "revenue must be finite." }
        require(quantity > 0) { "quantity must be positive." }
        val normalized = normalizeRevenueCurrency(revenue, currency)

        val eventData = LinkedHashMap<String, Any?>()
        metadata?.let { eventData.putAll(it) }
        eventData[AttriaxAnalyticsParamKeys.REVENUE] = normalized.revenue
        eventData[AttriaxAnalyticsParamKeys.CURRENCY] = normalized.currency
        if (revenueInMicros) eventData[AttriaxAnalyticsParamKeys.REVENUE_IN_MICROS] = true
        AttriaxRevenue.trimOrNull(purchaseType)?.let { eventData[AttriaxAnalyticsParamKeys.PURCHASE_TYPE] = it }
        AttriaxRevenue.trimOrNull(productId)?.let { eventData[AttriaxAnalyticsParamKeys.PRODUCT_ID] = it }
        AttriaxRevenue.trimOrNull(transactionId)?.let { eventData[AttriaxAnalyticsParamKeys.TRANSACTION_ID] = it }
        AttriaxRevenue.trimOrNull(originalTransactionId)
            ?.let { eventData[AttriaxAnalyticsParamKeys.ORIGINAL_TRANSACTION_ID] = it }
        AttriaxRevenue.trimOrNull(validationProvider)
            ?.let { eventData[AttriaxAnalyticsParamKeys.VALIDATION_PROVIDER] = it }
        AttriaxRevenue.trimOrNull(validationEnvironment)
            ?.let { eventData[AttriaxAnalyticsParamKeys.VALIDATION_ENVIRONMENT] = it }
        AttriaxRevenue.trimOrNull(purchaseToken)?.let { eventData[AttriaxAnalyticsParamKeys.PURCHASE_TOKEN] = it }
        AttriaxRevenue.trimOrNull(receiptData)?.let { eventData[AttriaxAnalyticsParamKeys.RECEIPT_DATA] = it }
        AttriaxRevenue.trimOrNull(signedPayload)?.let { eventData[AttriaxAnalyticsParamKeys.SIGNED_PAYLOAD] = it }
        AttriaxRevenue.trimOrNull(receiptSignature)
            ?.let { eventData[AttriaxAnalyticsParamKeys.RECEIPT_SIGNATURE] = it }
        isRenewal?.let { eventData[AttriaxAnalyticsParamKeys.IS_RENEWAL] = it }
        if (quantity != 1) eventData[AttriaxAnalyticsParamKeys.QUANTITY] = quantity
        AttriaxRevenue.trimOrNull(store)?.let { eventData[AttriaxAnalyticsParamKeys.STORE] = it }
        AttriaxRevenue.trimOrNull(packageName)?.let { eventData[AttriaxAnalyticsParamKeys.PACKAGE_NAME] = it }
        voided?.let { eventData[AttriaxAnalyticsParamKeys.VOIDED] = it }
        test?.let { eventData[AttriaxAnalyticsParamKeys.TEST] = it }
        AttriaxRevenue.trimOrNull(validationId)?.let { eventData[AttriaxAnalyticsParamKeys.VALIDATION_ID] = it }

        recordEvent(AttriaxAnalyticsEventKeys.PURCHASE, eventData = eventData, flushImmediately = flushImmediately)
    }

    /**
     * Record a refund (row E2): the revenue is NEGATED (`0` preserved as `0`) and
     * tagged `revenueType=refund`. Flushes immediately by default.
     */
    fun recordRefund(
        revenue: Double,
        currency: String = "USD",
        revenueInMicros: Boolean = false,
        purchaseType: String? = null,
        productId: String? = null,
        transactionId: String? = null,
        originalTransactionId: String? = null,
        quantity: Int = 1,
        store: String? = null,
        packageName: String? = null,
        voided: Boolean? = null,
        test: Boolean? = null,
        reason: String? = null,
        metadata: Map<String, Any?>? = null,
        flushImmediately: Boolean = true,
    ) {
        require(revenue.isFinite()) { "revenue must be finite." }
        require(quantity > 0) { "quantity must be positive." }
        val normalized = normalizeRevenueCurrency(revenue, currency)
        val refundRevenue = AttriaxRevenue.refundRevenue(normalized.revenue)

        val eventData = LinkedHashMap<String, Any?>()
        metadata?.let { eventData.putAll(it) }
        eventData[AttriaxAnalyticsParamKeys.REVENUE] = refundRevenue
        eventData[AttriaxAnalyticsParamKeys.CURRENCY] = normalized.currency
        eventData[AttriaxAnalyticsParamKeys.REVENUE_TYPE] = AttriaxAnalyticsEventKeys.REFUND
        if (revenueInMicros) eventData[AttriaxAnalyticsParamKeys.REVENUE_IN_MICROS] = true
        AttriaxRevenue.trimOrNull(purchaseType)?.let { eventData[AttriaxAnalyticsParamKeys.PURCHASE_TYPE] = it }
        AttriaxRevenue.trimOrNull(productId)?.let { eventData[AttriaxAnalyticsParamKeys.PRODUCT_ID] = it }
        AttriaxRevenue.trimOrNull(transactionId)?.let { eventData[AttriaxAnalyticsParamKeys.TRANSACTION_ID] = it }
        AttriaxRevenue.trimOrNull(originalTransactionId)
            ?.let { eventData[AttriaxAnalyticsParamKeys.ORIGINAL_TRANSACTION_ID] = it }
        if (quantity != 1) eventData[AttriaxAnalyticsParamKeys.QUANTITY] = quantity
        AttriaxRevenue.trimOrNull(store)?.let { eventData[AttriaxAnalyticsParamKeys.STORE] = it }
        AttriaxRevenue.trimOrNull(packageName)?.let { eventData[AttriaxAnalyticsParamKeys.PACKAGE_NAME] = it }
        voided?.let { eventData[AttriaxAnalyticsParamKeys.VOIDED] = it }
        test?.let { eventData[AttriaxAnalyticsParamKeys.TEST] = it }
        AttriaxRevenue.trimOrNull(reason)?.let { eventData[AttriaxAnalyticsParamKeys.REASON] = it }

        recordEvent(AttriaxAnalyticsEventKeys.REFUND, eventData = eventData, flushImmediately = flushImmediately)
    }

    /** Record realized ad revenue (row E1). Flushes immediately by default. */
    fun recordAdRevenue(
        revenue: Double,
        currency: String = "USD",
        revenueInMicros: Boolean = false,
        adNetwork: String? = null,
        adFormat: String? = null,
        adType: String? = null,
        adPlacement: String? = null,
        test: Boolean? = null,
        metadata: Map<String, Any?>? = null,
        flushImmediately: Boolean = true,
    ) {
        require(revenue.isFinite()) { "revenue must be finite." }
        val normalized = normalizeRevenueCurrency(revenue, currency)

        val eventData = LinkedHashMap<String, Any?>()
        metadata?.let { eventData.putAll(it) }
        eventData[AttriaxAnalyticsParamKeys.REVENUE] = normalized.revenue
        eventData[AttriaxAnalyticsParamKeys.CURRENCY] = normalized.currency
        if (revenueInMicros) eventData[AttriaxAnalyticsParamKeys.REVENUE_IN_MICROS] = true
        AttriaxRevenue.trimOrNull(adNetwork)?.let { eventData[AttriaxAnalyticsParamKeys.AD_NETWORK] = it }
        AttriaxRevenue.trimOrNull(adFormat)?.let { eventData[AttriaxAnalyticsParamKeys.AD_FORMAT] = it }
        AttriaxRevenue.trimOrNull(adType)?.let { eventData[AttriaxAnalyticsParamKeys.AD_TYPE] = it }
        AttriaxRevenue.trimOrNull(adPlacement)?.let { eventData[AttriaxAnalyticsParamKeys.AD_PLACEMENT] = it }
        test?.let { eventData[AttriaxAnalyticsParamKeys.TEST] = it }

        recordEvent(AttriaxAnalyticsEventKeys.AD_REVENUE, eventData = eventData, flushImmediately = flushImmediately)
    }

    /** Record an ad-lifecycle event under its reserved name (row E1). Flushes immediately by default. */
    fun recordAdEvent(
        type: AttriaxAdEventType,
        adNetwork: String? = null,
        mediationNetwork: String? = null,
        adUnitId: String? = null,
        adPlacement: String? = null,
        adFormat: String? = null,
        adType: String? = null,
        failureReason: String? = null,
        loadLatencyMs: Double? = null,
        rewardType: String? = null,
        rewardAmount: Double? = null,
        test: Boolean? = null,
        metadata: Map<String, Any?>? = null,
        flushImmediately: Boolean = true,
    ) {
        require(loadLatencyMs == null || loadLatencyMs.isFinite()) { "loadLatencyMs must be finite." }
        require(rewardAmount == null || rewardAmount.isFinite()) { "rewardAmount must be finite." }

        val eventData = LinkedHashMap<String, Any?>()
        metadata?.let { eventData.putAll(it) }
        AttriaxRevenue.trimOrNull(adNetwork)?.let { eventData[AttriaxAnalyticsParamKeys.AD_NETWORK] = it }
        AttriaxRevenue.trimOrNull(mediationNetwork)
            ?.let { eventData[AttriaxAnalyticsParamKeys.MEDIATION_NETWORK] = it }
        AttriaxRevenue.trimOrNull(adUnitId)?.let { eventData[AttriaxAnalyticsParamKeys.AD_UNIT_ID] = it }
        AttriaxRevenue.trimOrNull(adPlacement)?.let { eventData[AttriaxAnalyticsParamKeys.AD_PLACEMENT] = it }
        AttriaxRevenue.trimOrNull(adFormat)?.let { eventData[AttriaxAnalyticsParamKeys.AD_FORMAT] = it }
        AttriaxRevenue.trimOrNull(adType)?.let { eventData[AttriaxAnalyticsParamKeys.AD_TYPE] = it }
        AttriaxRevenue.trimOrNull(failureReason)?.let { eventData[AttriaxAnalyticsParamKeys.FAILURE_REASON] = it }
        AttriaxRevenue.trimOrNull(rewardType)?.let { eventData[AttriaxAnalyticsParamKeys.REWARD_TYPE] = it }
        loadLatencyMs?.let { eventData[AttriaxAnalyticsParamKeys.LOAD_LATENCY_MS] = it }
        rewardAmount?.let { eventData[AttriaxAnalyticsParamKeys.REWARD_AMOUNT] = it }
        test?.let { eventData[AttriaxAnalyticsParamKeys.TEST] = it }

        recordEvent(type.eventName, eventData = eventData, flushImmediately = flushImmediately)
    }

    // -------- errors / crashes (POST /api/sdk/v1/crashes) --------

    /**
     * Record an error/crash (POST `/api/sdk/v1/crashes`). `fatal = false` is a normal
     * non-fatal enqueue; `fatal = true` PERSISTS ONLY (no immediate enqueue) and is
     * delivered exclusively via replay on the next init — exactly-once through the
     * durable queue — the path Flutter/Unity/RN wrappers call to forward their own
     * framework-level fatal crashes.
     * Delegates to the engine so the manual, auto-handler, and replay paths share one
     * crash wire shape.
     */
    fun recordError(
        error: Throwable,
        stackTrace: String? = null,
        fatal: Boolean = false,
        source: String = "manual",
        reason: String? = null,
        metadata: Map<String, Any?>? = null,
    ) {
        engine.recordError(
            error = error,
            stackTrace = stackTrace,
            fatal = fatal,
            source = source,
            reason = reason,
            metadata = metadata,
        )
    }

    // -------- notifications (POST /api/sdk/v1/notifications; rows E6) --------

    fun recordNotification(
        type: AttriaxNotificationEventType,
        notificationId: String,
        linkId: String? = null,
        campaignId: String? = null,
        title: String? = null,
        source: AttriaxNotificationEventSource? = null,
        payload: Map<String, Any?>? = null,
        metadata: Map<String, Any?>? = null,
        flushImmediately: Boolean = false,
    ) {
        if (!engine.isTrackingEnabled) return
        val normalizedNotificationId = notificationId.trim()
        require(normalizedNotificationId.isNotEmpty()) { "notificationId must not be empty." }

        val resolvedSource = source ?: AttriaxRevenue.inferNotificationSource(payload)
        val mergedMetadata = AttriaxRevenue.mergeNotificationMetadata(metadata = metadata, payload = payload)

        val request = AttriaxRequestBuilders.buildNotification(
            projectToken = projectToken(),
            platform = engine.contextSnapshot.platform,
            type = type.wireValue,
            notificationId = normalizedNotificationId,
            deviceId = engine.resolvedDeviceId,
            deviceIdSource = engine.resolvedDeviceIdSource,
            linkId = AttriaxRevenue.trimOrNull(linkId),
            campaignId = AttriaxRevenue.trimOrNull(campaignId),
            title = AttriaxRevenue.trimOrNull(title),
            source = resolvedSource?.wireValue,
            sessionId = null,
            occurredAtIso = engine.nowIsoNow(),
            metadata = mergedMetadata,
        )
        // Notifications are event-family signals: first-launch eager flush applies
        // (PARITY — Flutter recordNotification `_shouldFlushEventImmediately`).
        engine.enqueueRequest(request, flushImmediately = engine.shouldFlushEventImmediately(flushImmediately))
    }

    fun recordNotificationReceived(
        notificationId: String,
        linkId: String? = null,
        campaignId: String? = null,
        title: String? = null,
        source: AttriaxNotificationEventSource? = null,
        payload: Map<String, Any?>? = null,
        metadata: Map<String, Any?>? = null,
        flushImmediately: Boolean = false,
    ) = recordNotification(
        type = AttriaxNotificationEventType.RECEIVED,
        notificationId = notificationId,
        linkId = linkId,
        campaignId = campaignId,
        title = title,
        source = source,
        payload = payload,
        metadata = metadata,
        flushImmediately = flushImmediately,
    )

    fun recordNotificationOpened(
        notificationId: String,
        linkId: String? = null,
        campaignId: String? = null,
        title: String? = null,
        source: AttriaxNotificationEventSource? = null,
        payload: Map<String, Any?>? = null,
        metadata: Map<String, Any?>? = null,
        flushImmediately: Boolean = false,
    ) = recordNotification(
        type = AttriaxNotificationEventType.OPENED,
        notificationId = notificationId,
        linkId = linkId,
        campaignId = campaignId,
        title = title,
        source = source,
        payload = payload,
        metadata = metadata,
        flushImmediately = flushImmediately,
    )

    fun recordNotificationDismissed(
        notificationId: String,
        linkId: String? = null,
        campaignId: String? = null,
        title: String? = null,
        source: AttriaxNotificationEventSource? = null,
        payload: Map<String, Any?>? = null,
        metadata: Map<String, Any?>? = null,
        flushImmediately: Boolean = false,
    ) = recordNotification(
        type = AttriaxNotificationEventType.DISMISSED,
        notificationId = notificationId,
        linkId = linkId,
        campaignId = campaignId,
        title = title,
        source = source,
        payload = payload,
        metadata = metadata,
        flushImmediately = flushImmediately,
    )

    // -------- identify (POST /api/sdk/v1/users) --------

    /** Associate the current device with a user (row identify). `userId==null` clears it. */
    fun setUser(userId: String?, userName: String? = null) {
        if (!engine.isTrackingEnabled) return
        enqueueUserUpdate(
            externalUserId = AttriaxRevenue.trimOrNull(userId),
            externalUserName = AttriaxRevenue.trimOrNull(userName),
            clearExternalUser = userId == null,
        )
    }

    /** Set a single user property; a `null` value clears the named property. */
    fun setUserProperty(name: String, value: Any?) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        if (value == null) {
            clearUserProperties(propertyNames = listOf(trimmedName))
            return
        }
        setUserProperties(mapOf(trimmedName to value))
    }

    /** Merge user properties into future events (blank keys dropped). */
    fun setUserProperties(properties: Map<String, Any?>) {
        if (!engine.isTrackingEnabled) return
        val sanitized = LinkedHashMap<String, Any?>()
        for ((key, value) in properties) {
            val trimmed = key.trim()
            if (trimmed.isNotEmpty()) sanitized[trimmed] = value
        }
        if (sanitized.isEmpty()) return
        enqueueUserUpdate(properties = sanitized)
    }

    /** Clear user properties. `null`/empty [propertyNames] clears ALL stored properties. */
    fun clearUserProperties(propertyNames: List<String>? = null) {
        if (!engine.isTrackingEnabled) return
        val normalized = propertyNames
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
        enqueueUserUpdate(
            clearPropertyKeys = normalized,
            clearAllProperties = normalized == null,
        )
    }

    // -------- uninstall token (POST /api/sdk/v1/uninstall-tokens) --------

    /** Register (or, with a null token, de-register) the FCM uninstall-tracking token. */
    fun registerFirebaseMessagingToken(token: String?, metadata: Map<String, Any?>? = null) {
        if (!engine.isTrackingEnabled) return
        val deviceId = engine.resolvedDeviceId ?: return
        val request = AttriaxRequestBuilders.buildUninstallToken(
            projectToken = projectToken(),
            deviceId = deviceId,
            deviceIdSource = engine.resolvedDeviceIdSource,
            platform = engine.contextSnapshot.platform,
            provider = UNINSTALL_TOKEN_PROVIDER_FCM,
            token = AttriaxRevenue.trimOrNull(token),
            metadata = metadata,
        )
        engine.enqueueRequest(request, flushImmediately = false)
    }

    // -------- internals --------

    private fun enqueueUserUpdate(
        externalUserId: String? = null,
        externalUserName: String? = null,
        clearExternalUser: Boolean = false,
        properties: Map<String, Any?>? = null,
        clearPropertyKeys: List<String>? = null,
        clearAllProperties: Boolean = false,
    ) {
        // SdkUserDto requires deviceId; identify is not part of the anonymous-capable
        // signal set, so it needs the resolved identity.
        val deviceId = engine.resolvedDeviceId ?: return
        val request = AttriaxRequestBuilders.buildUser(
            projectToken = projectToken(),
            externalUserId = externalUserId,
            externalUserName = externalUserName,
            properties = properties,
            deviceId = deviceId,
            deviceIdSource = engine.resolvedDeviceIdSource,
            clearExternalUser = clearExternalUser,
            clearPropertyKeys = clearPropertyKeys,
            clearAllProperties = clearAllProperties,
        )
        engine.enqueueRequest(request, flushImmediately = false)
    }

    private fun projectToken(): String = engine.projectTokenForTracking

    /**
     * Validate [currency] and warn on the invalid → 0 USD default (row E3). The
     * pure normalization lives in [AttriaxRevenue]; the warning is the only side
     * effect kept here so the lowering stays JVM-testable.
     */
    private fun normalizeRevenueCurrency(revenue: Double, currency: String): AttriaxRevenue.NormalizedRevenue {
        if (!AttriaxRevenue.isValidCurrency(currency)) {
            engine.logger.warn(
                "Invalid revenue currency \"$currency\"; defaulting revenue to 0 USD.",
            )
        }
        return AttriaxRevenue.normalizeRevenueCurrency(revenue, currency)
    }

    private companion object {
        const val UNINSTALL_TOKEN_PROVIDER_FCM = "fcm"
    }
}
