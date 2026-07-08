package com.attriax.sdk.internal.request

import com.attriax.sdk.internal.AttriaxContextSnapshot

/**
 * Pure builders that assemble the JSON body maps for the core request kinds
 * (PARITY §3/§4, rows E4/E5). Field placement matters:
 *  - event/user payloads OMIT platform/version by design (backend derives from
 *    AppUser); open/session carry the full context.
 *  - identity fields (`projectToken`/`deviceId`/`deviceIdSource`) are stamped at
 *    BUILD time and frozen (row D3) — never re-stamped at flush.
 */
object AttriaxRequestBuilders {

    /** App-open (`/api/sdk/v1/open`) — carries full context + identity + session hints. */
    fun buildOpen(
        projectToken: String,
        context: AttriaxContextSnapshot,
        deviceId: String,
        deviceIdSource: String,
        isFirstLaunch: Boolean,
        sessionId: String?,
        sessionStartedAtIso: String?,
        installReferrer: String? = null,
        installBeginTimestampSeconds: Long? = null,
        referrerClickTimestampSeconds: Long? = null,
        googlePlayInstantParam: Boolean? = null,
        attestation: Map<String, Any?>? = null,
        sdkMetadata: Map<String, Any?>? = null,
    ): AttriaxApiRequest {
        // The open DTO (api SdkV1OpenDto) NESTS context under `sdk`/`app`/`device`
        // sub-objects — the backend rejects unknown top-level properties
        // (whitelist validation), so these MUST be nested, not flat.
        val body = LinkedHashMap<String, Any?>()
        body["projectToken"] = projectToken
        body["platform"] = context.platform
        body["deviceId"] = deviceId
        body["deviceIdSource"] = deviceIdSource
        body["isFirstLaunch"] = isFirstLaunch

        // The `sdk` block carries apiVersion/packageVersion and the optional
        // host-supplied metadata (Flutter's AttriaxSdkSnapshot.metadata — sourced
        // from AttriaxConfig.sdkMetadata). Absent metadata is OMITTED, not sent null.
        val sdk = LinkedHashMap<String, Any?>()
        sdk["apiVersion"] = context.sdkApiVersion
        sdk["packageVersion"] = context.sdkPackageVersion
        sdkMetadata?.let { sdk["metadata"] = it }
        body["sdk"] = sdk

        val app = LinkedHashMap<String, Any?>()
        context.appVersion?.let { app["version"] = it }
        context.appBuildNumber?.let { app["buildNumber"] = it }
        context.packageName?.let { app["packageName"] = it }
        body["app"] = app

        val device = LinkedHashMap<String, Any?>()
        context.deviceModel?.let { device["model"] = it }
        context.deviceManufacturer?.let { device["manufacturer"] = it }
        device["osVersion"] = context.osVersion
        context.deviceTimezone?.let { device["timezone"] = it }
        // DeviceContextDto names this `language` (not `locale`).
        context.deviceLocale?.let { device["language"] = it }
        body["device"] = device

        sessionId?.let { body["sessionId"] = it }
        sessionStartedAtIso?.let { body["sessionStartedAt"] = it }
        // Google Play install-referrer enrichment (top-level per SdkV1OpenDto).
        // Absent fields are OMITTED rather than sent as null (whitelist DTO).
        installReferrer?.takeIf { it.isNotEmpty() }?.let { body["installReferrer"] = it }
        installBeginTimestampSeconds?.let { body["installBeginTimestampSeconds"] = it }
        referrerClickTimestampSeconds?.let { body["referrerClickTimestampSeconds"] = it }
        googlePlayInstantParam?.let { body["googlePlayInstantParam"] = it }
        attestation?.let { body["attestation"] = it }
        return AttriaxApiRequest(AttriaxApiRequest.KIND_OPEN, AttriaxEndpoints.OPEN, body)
    }

    /**
     * Event (`/api/sdk/v1/events`). platform/version OMITTED (row E4). Identity
     * (`deviceId`/`deviceIdSource`) is nullable to support anonymous capture, but
     * batching requires it present.
     */
    fun buildEvent(
        projectToken: String,
        eventName: String,
        eventData: Map<String, Any?>?,
        deviceId: String?,
        deviceIdSource: String?,
        sessionId: String?,
        sessionRelativeTimeMs: Long?,
        clientOccurredAtIso: String,
    ): AttriaxApiRequest {
        val body = LinkedHashMap<String, Any?>()
        body["projectToken"] = projectToken
        body["eventName"] = eventName
        eventData?.let { body["eventData"] = it }
        deviceId?.let { body["deviceId"] = it }
        deviceIdSource?.let { body["deviceIdSource"] = it }
        sessionId?.let { body["sessionId"] = it }
        sessionRelativeTimeMs?.let { body["sessionRelativeTimeMs"] = it }
        body["clientOccurredAt"] = clientOccurredAtIso
        return AttriaxApiRequest(AttriaxApiRequest.KIND_TRACK_EVENT, AttriaxEndpoints.EVENTS, body)
    }

    /**
     * Session lifecycle (`/api/sdk/v1/sessions`). FLAT per the api `SdkSessionDto`
     * (whitelist-validated — every field name below matches the DTO exactly and
     * every enriched field is optional there). Mirrors the Flutter reference
     * `attriaxBuildTrackSessionRequest`: identity is nullable for anonymous capture,
     * `sessionRelativeTimeMs` is the clamped ms-since-start, and the app/device/sdk
     * context is carried inline (open/session/crash carry context; event/user omit
     * it — row E4). Absent optionals are OMITTED rather than sent as null.
     */
    fun buildSession(
        projectToken: String,
        kind: String,
        sessionId: String,
        deviceId: String?,
        deviceIdSource: String?,
        clientOccurredAtIso: String,
        sessionRelativeTimeMs: Long? = null,
        platform: String? = null,
        locale: String? = null,
        isFirstLaunch: Boolean? = null,
        appVersion: String? = null,
        appBuildNumber: String? = null,
        appPackageName: String? = null,
        sdkApiVersion: String? = null,
        sdkPackageVersion: String? = null,
        metadata: Map<String, Any?>? = null,
    ): AttriaxApiRequest {
        val body = LinkedHashMap<String, Any?>()
        body["projectToken"] = projectToken
        body["kind"] = kind
        body["sessionId"] = sessionId
        deviceId?.let { body["deviceId"] = it }
        deviceIdSource?.let { body["deviceIdSource"] = it }
        sessionRelativeTimeMs?.let { body["sessionRelativeTimeMs"] = it }
        body["clientOccurredAt"] = clientOccurredAtIso
        platform?.let { body["platform"] = it }
        locale?.let { body["locale"] = it }
        isFirstLaunch?.let { body["isFirstLaunch"] = it }
        appVersion?.let { body["appVersion"] = it }
        appBuildNumber?.let { body["appBuildNumber"] = it }
        appPackageName?.let { body["appPackageName"] = it }
        sdkApiVersion?.let { body["sdkApiVersion"] = it }
        sdkPackageVersion?.let { body["sdkPackageVersion"] = it }
        metadata?.let { body["metadata"] = it }
        return AttriaxApiRequest(AttriaxApiRequest.KIND_TRACK_SESSION, AttriaxEndpoints.SESSIONS, body)
    }

    /**
     * User/identify (`/api/sdk/v1/users`). platform/version OMITTED (row E4).
     * Wire field names match the api SdkUserDto: `externalUserId`/`externalUserName`/
     * `properties` (NOT userId/userProperties), and there is NO `clientOccurredAt`
     * on this DTO (the backend rejects unknown properties). The richer identify
     * surface (clearAllProperties/clearPropertyKeys/clearExternalUser) lands with
     * the tracking slice.
     */
    fun buildUser(
        projectToken: String,
        externalUserId: String?,
        externalUserName: String?,
        properties: Map<String, Any?>?,
        deviceId: String?,
        deviceIdSource: String?,
        clearExternalUser: Boolean = false,
        clearPropertyKeys: List<String>? = null,
        clearAllProperties: Boolean = false,
    ): AttriaxApiRequest {
        // Field names + optionality match the api SdkUserDto: `deviceId` is
        // REQUIRED, identify fields are `externalUserId`/`externalUserName`, and
        // the clear flags are only emitted when true (unknown props are rejected).
        val body = LinkedHashMap<String, Any?>()
        body["projectToken"] = projectToken
        deviceId?.let { body["deviceId"] = it }
        deviceIdSource?.let { body["deviceIdSource"] = it }
        externalUserId?.let { body["externalUserId"] = it }
        externalUserName?.let { body["externalUserName"] = it }
        if (clearExternalUser) body["clearExternalUser"] = true
        properties?.let { body["properties"] = it }
        clearPropertyKeys?.takeIf { it.isNotEmpty() }?.let { body["clearPropertyKeys"] = it }
        if (clearAllProperties) body["clearAllProperties"] = true
        return AttriaxApiRequest(AttriaxApiRequest.KIND_USER, AttriaxEndpoints.USERS, body)
    }

    /**
     * Crash/error (`/api/sdk/v1/crashes`). The api SdkCrashDto is FLAT and carries
     * the full app/device/sdk context inline (unlike open, which nests). `platform`
     * and `isFirstLaunch` are required; identity is nullable for anonymous capture.
     */
    fun buildCrash(
        projectToken: String,
        context: AttriaxContextSnapshot,
        deviceId: String?,
        deviceIdSource: String?,
        source: String,
        isFatal: Boolean,
        exceptionType: String,
        message: String,
        stackTrace: String,
        isFirstLaunch: Boolean,
        clientOccurredAtIso: String,
        reason: String?,
        sessionId: String?,
        sessionRelativeTimeMs: Long?,
        metadata: Map<String, Any?>?,
    ): AttriaxApiRequest {
        val body = LinkedHashMap<String, Any?>()
        body["projectToken"] = projectToken
        deviceId?.let { body["deviceId"] = it }
        deviceIdSource?.let { body["deviceIdSource"] = it }
        body["source"] = source
        body["clientOccurredAt"] = clientOccurredAtIso
        body["platform"] = context.platform
        body["isFatal"] = isFatal
        body["exceptionType"] = exceptionType
        body["message"] = message
        body["stackTrace"] = stackTrace
        body["isFirstLaunch"] = isFirstLaunch
        reason?.let { body["reason"] = it }
        sessionId?.let { body["sessionId"] = it }
        sessionRelativeTimeMs?.let { body["sessionRelativeTimeMs"] = it }
        context.deviceLocale?.let { body["locale"] = it }
        context.appVersion?.let { body["appVersion"] = it }
        context.appBuildNumber?.let { body["appBuildNumber"] = it }
        context.packageName?.let { body["appPackageName"] = it }
        body["sdkApiVersion"] = context.sdkApiVersion
        body["sdkPackageVersion"] = context.sdkPackageVersion
        metadata?.let { body["metadata"] = it }
        return AttriaxApiRequest(AttriaxApiRequest.KIND_TRACK_CRASH, AttriaxEndpoints.CRASHES, body)
    }

    /**
     * Notification lifecycle (`/api/sdk/v1/notifications`). FLAT per the api
     * SdkNotificationDto: `type`/`notificationId`/`platform` required, `source`
     * (fcm/apns/other) + `occurredAt` optional; raw payload lives under
     * `metadata.payload` (assembled by the caller). No `clientOccurredAt`/
     * `sessionRelativeTimeMs` on this DTO.
     */
    fun buildNotification(
        projectToken: String,
        platform: String,
        type: String,
        notificationId: String,
        deviceId: String?,
        deviceIdSource: String?,
        linkId: String?,
        campaignId: String?,
        title: String?,
        source: String?,
        sessionId: String?,
        occurredAtIso: String?,
        metadata: Map<String, Any?>?,
    ): AttriaxApiRequest {
        val body = LinkedHashMap<String, Any?>()
        body["projectToken"] = projectToken
        deviceId?.let { body["deviceId"] = it }
        deviceIdSource?.let { body["deviceIdSource"] = it }
        sessionId?.let { body["sessionId"] = it }
        body["type"] = type
        body["notificationId"] = notificationId
        linkId?.let { body["linkId"] = it }
        campaignId?.let { body["campaignId"] = it }
        title?.let { body["title"] = it }
        source?.let { body["source"] = it }
        body["platform"] = platform
        occurredAtIso?.let { body["occurredAt"] = it }
        metadata?.let { body["metadata"] = it }
        return AttriaxApiRequest(
            AttriaxApiRequest.KIND_TRACK_NOTIFICATION,
            AttriaxEndpoints.NOTIFICATIONS,
            body,
        )
    }

    /**
     * Uninstall-token registration (`/api/sdk/v1/uninstall-tokens`). FLAT per the
     * api SdkRegisterUninstallTokenDto: `deviceId`/`platform`/`provider` required,
     * `token` nullable (a null token de-registers). Provider defaults to `fcm`.
     */
    fun buildUninstallToken(
        projectToken: String,
        deviceId: String,
        deviceIdSource: String?,
        platform: String,
        provider: String,
        token: String?,
        metadata: Map<String, Any?>?,
    ): AttriaxApiRequest {
        val body = LinkedHashMap<String, Any?>()
        body["projectToken"] = projectToken
        body["deviceId"] = deviceId
        deviceIdSource?.let { body["deviceIdSource"] = it }
        body["platform"] = platform
        body["provider"] = provider
        token?.let { body["token"] = it }
        metadata?.let { body["metadata"] = it }
        return AttriaxApiRequest(
            AttriaxApiRequest.KIND_REGISTER_UNINSTALL_TOKEN,
            AttriaxEndpoints.UNINSTALL_TOKENS,
            body,
        )
    }

    /**
     * Deep-link resolve (`/api/sdk/v1/deep-links/resolve`). Wire shape matches the
     * api `SdkV1DeepLinkResolveDto`: `projectToken` (required), `platform`
     * (required), and the optional `deviceId`/`deviceIdSource`/`rawUrl`/`linkPath`/
     * `source`/`sessionId`/`sessionRelativeTimeMs`/`isFirstLaunch`/`metadata`.
     * Unknown props are rejected by whitelist validation, so absent optionals are
     * OMITTED rather than sent as null. Identity is nullable to support anonymous
     * deep-link diagnostics while consent is pending (PARITY §5/§6).
     */
    fun buildResolveDeepLink(
        projectToken: String,
        platform: String,
        source: String?,
        isFirstLaunch: Boolean,
        deviceId: String?,
        deviceIdSource: String?,
        rawUrl: String?,
        linkPath: String?,
        sessionId: String?,
        sessionRelativeTimeMs: Long?,
        metadata: Map<String, Any?>?,
    ): AttriaxApiRequest {
        val body = LinkedHashMap<String, Any?>()
        body["projectToken"] = projectToken
        deviceId?.let { body["deviceId"] = it }
        deviceIdSource?.let { body["deviceIdSource"] = it }
        body["platform"] = platform
        rawUrl?.let { body["rawUrl"] = it }
        linkPath?.let { body["linkPath"] = it }
        source?.let { body["source"] = it }
        sessionId?.let { body["sessionId"] = it }
        sessionRelativeTimeMs?.let { body["sessionRelativeTimeMs"] = it }
        body["isFirstLaunch"] = isFirstLaunch
        metadata?.let { body["metadata"] = it }
        return AttriaxApiRequest(
            AttriaxApiRequest.KIND_RESOLVE_DEEP_LINK,
            AttriaxEndpoints.DEEP_LINKS_RESOLVE,
            body,
        )
    }

    /**
     * Create dynamic link (`/api/sdk/v1/dynamic-links`). Wire shape matches the api
     * `SdkCreateDynamicLinkDto`: `projectToken` (required) + all-optional
     * `name`/`destinationUrl`/`iosRedirect`/`androidRedirect`/`previewTitle`/
     * `previewDescription`/`group`/`prefix`/`data`/`utm{Source,Medium,Campaign,
     * Term,Content}`. The redirects/socialPreview/utms value objects are flattened
     * to these flat wire keys (mirrors the Flutter `attriaxBuildCreateDynamicLinkRequest`).
     * Sent DIRECTLY (non-queued) — it is a synchronous request/response.
     */
    fun buildCreateDynamicLink(
        projectToken: String,
        name: String?,
        destinationUrl: String?,
        group: String?,
        prefix: String?,
        iosRedirect: Boolean?,
        androidRedirect: Boolean?,
        previewTitle: String?,
        previewDescription: String?,
        utmSource: String?,
        utmMedium: String?,
        utmCampaign: String?,
        utmTerm: String?,
        utmContent: String?,
        data: Map<String, Any?>?,
    ): Map<String, Any?> {
        val body = LinkedHashMap<String, Any?>()
        body["projectToken"] = projectToken
        name?.let { body["name"] = it }
        destinationUrl?.let { body["destinationUrl"] = it }
        iosRedirect?.let { body["iosRedirect"] = it }
        androidRedirect?.let { body["androidRedirect"] = it }
        previewTitle?.let { body["previewTitle"] = it }
        previewDescription?.let { body["previewDescription"] = it }
        group?.let { body["group"] = it }
        prefix?.let { body["prefix"] = it }
        data?.let { body["data"] = it }
        utmSource?.let { body["utmSource"] = it }
        utmMedium?.let { body["utmMedium"] = it }
        utmCampaign?.let { body["utmCampaign"] = it }
        utmTerm?.let { body["utmTerm"] = it }
        utmContent?.let { body["utmContent"] = it }
        return body
    }

    /**
     * Receipt validation body (`/api/sdk/v1/revenue/receipts/validate`). Sent
     * DIRECTLY (non-queued) by [com.attriax.sdk.Attriax.validateReceipt]. FLAT per
     * the api SdkV1RevenueReceiptValidateDto — every field except the token is
     * optional; unknown fields would be rejected.
     */
    fun buildReceiptValidate(
        projectToken: String,
        receipt: String,
        deviceId: String?,
        clientOccurredAtIso: String,
        provider: String?,
        environment: String?,
        transactionId: String?,
        productId: String?,
        test: Boolean?,
    ): Map<String, Any?> {
        val body = LinkedHashMap<String, Any?>()
        body["projectToken"] = projectToken
        deviceId?.let { body["deviceId"] = it }
        body["clientOccurredAt"] = clientOccurredAtIso
        body["receipt"] = receipt
        provider?.let { body["provider"] = it }
        environment?.let { body["environment"] = it }
        transactionId?.let { body["transactionId"] = it }
        productId?.let { body["productId"] = it }
        test?.let { body["test"] = it }
        return body
    }
}
