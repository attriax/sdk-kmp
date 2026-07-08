package com.attriax.sdk.internal.consent

import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.request.AttriaxApiRequest
import com.attriax.sdk.internal.request.AttriaxEndpoints
import com.attriax.sdk.internal.json.Json

/**
 * The remote consent status echo (PARITY §5, row C2). Mirrors the api
 * `SdkGdprConsentStatusDto` (returned inside the `{data:...}` envelope the
 * transport already unwraps). Only the fields the SDK consumes are modeled.
 */
data class AttriaxRemoteConsentStatus(
    val state: AttriaxGdprConsentState,
    val values: AttriaxGdprConsentValues?,
    val needsConsent: Boolean,
    val countryCode: String?,
    val regionSource: String?,
    val checkedAtIso: String?,
)

/**
 * Narrow port the [AttriaxConsentManager] uses to talk to the backend. Kept as an
 * interface (no platform / HttpClient in the signature) so the generation-guard
 * race can be reproduced deterministically in tests with a fake that controls
 * exactly when each echo returns.
 *
 * WIRE-SHAPE RULE (grounded in the api DTOs, not prose):
 *  * check  → POST /api/sdk/v1/consent/gdpr/check   body: {projectToken, consentId?}
 *  * upsert → POST /api/sdk/v1/consent/gdpr          body: {projectToken, consentId?,
 *             state, values?{analytics,attribution,adEvents}, countryCode?,
 *             regionSource?, clientOccurredAt?}
 *  * erase  → POST /api/sdk/v1/privacy/gdpr/erase    body: {projectToken, deviceId?,
 *             externalUserId?}
 *
 * Consent check/upsert send an SDK-generated `consentId` and carry NO deviceId or
 * user identifier (the api check/write DTOs reject unknown props). `state` uses
 * the api snake_case tokens (`not_required`, not `notRequired`).
 */
interface AttriaxConsentTransport {
    fun checkGdprConsent(projectToken: String, consentId: String): AttriaxRemoteConsentStatus

    fun upsertGdprConsent(
        projectToken: String,
        consentId: String,
        state: AttriaxGdprConsentState,
        values: AttriaxGdprConsentValues?,
        countryCode: String?,
        regionSource: String?,
        clientOccurredAtIso: String?,
    ): AttriaxRemoteConsentStatus

    fun eraseGdprData(projectToken: String, deviceId: String)
}

/**
 * The on-device [AttriaxConsentTransport] backed by the shared [HttpClient]. It
 * assembles the exact DTO bodies above and parses the status echo out of the
 * (already envelope-unwrapped) response body.
 */
class AttriaxHttpConsentTransport(
    private val http: HttpClient,
) : AttriaxConsentTransport {

    override fun checkGdprConsent(
        projectToken: String,
        consentId: String,
    ): AttriaxRemoteConsentStatus {
        val body = LinkedHashMap<String, Any?>()
        body[AttriaxApiRequest.FIELD_PROJECT_TOKEN] = projectToken
        body[FIELD_CONSENT_ID] = consentId
        val response = http.post(AttriaxEndpoints.CONSENT_CHECK, Json.encode(body))
        return parseStatus(response.body)
    }

    override fun upsertGdprConsent(
        projectToken: String,
        consentId: String,
        state: AttriaxGdprConsentState,
        values: AttriaxGdprConsentValues?,
        countryCode: String?,
        regionSource: String?,
        clientOccurredAtIso: String?,
    ): AttriaxRemoteConsentStatus {
        val body = LinkedHashMap<String, Any?>()
        body[AttriaxApiRequest.FIELD_PROJECT_TOKEN] = projectToken
        body[FIELD_CONSENT_ID] = consentId
        body[FIELD_STATE] = AttriaxConsentStateWire.toWire(state)
        values?.let {
            body[FIELD_VALUES] = linkedMapOf<String, Any?>(
                FIELD_ANALYTICS to it.analytics,
                FIELD_ATTRIBUTION to it.attribution,
                FIELD_AD_EVENTS to it.adEvents,
            )
        }
        countryCode?.let { body[FIELD_COUNTRY_CODE] = it }
        regionSource?.let { body[FIELD_REGION_SOURCE] = it }
        clientOccurredAtIso?.let { body[FIELD_CLIENT_OCCURRED_AT] = it }
        val response = http.post(AttriaxEndpoints.CONSENT_UPSERT, Json.encode(body))
        return parseStatus(response.body)
    }

    override fun eraseGdprData(projectToken: String, deviceId: String) {
        val body = LinkedHashMap<String, Any?>()
        body[AttriaxApiRequest.FIELD_PROJECT_TOKEN] = projectToken
        body[AttriaxApiRequest.FIELD_DEVICE_ID] = deviceId
        http.post(AttriaxEndpoints.GDPR_ERASE, Json.encode(body))
    }

    private fun parseStatus(rawBody: String?): AttriaxRemoteConsentStatus {
        val obj = if (rawBody.isNullOrBlank()) emptyMap() else Json.decodeObject(rawBody)
        val valuesObj = obj[FIELD_VALUES] as? Map<*, *>
        val values = valuesObj?.let {
            AttriaxGdprConsentValues(
                analytics = (it[FIELD_ANALYTICS] as? Boolean) ?: false,
                attribution = (it[FIELD_ATTRIBUTION] as? Boolean) ?: false,
                adEvents = (it[FIELD_AD_EVENTS] as? Boolean) ?: false,
            )
        }
        return AttriaxRemoteConsentStatus(
            state = AttriaxConsentStateWire.fromWire(obj[FIELD_STATE] as? String),
            values = values,
            needsConsent = (obj[FIELD_NEEDS_CONSENT] as? Boolean) ?: false,
            countryCode = obj[FIELD_COUNTRY_CODE] as? String,
            regionSource = obj[FIELD_REGION_SOURCE] as? String,
            checkedAtIso = obj[FIELD_CHECKED_AT] as? String,
        )
    }

    private companion object {
        const val FIELD_CONSENT_ID = "consentId"
        const val FIELD_STATE = "state"
        const val FIELD_VALUES = "values"
        const val FIELD_ANALYTICS = "analytics"
        const val FIELD_ATTRIBUTION = "attribution"
        const val FIELD_AD_EVENTS = "adEvents"
        const val FIELD_COUNTRY_CODE = "countryCode"
        const val FIELD_REGION_SOURCE = "regionSource"
        const val FIELD_CLIENT_OCCURRED_AT = "clientOccurredAt"
        const val FIELD_NEEDS_CONSENT = "needsConsent"
        const val FIELD_CHECKED_AT = "checkedAt"
    }
}
