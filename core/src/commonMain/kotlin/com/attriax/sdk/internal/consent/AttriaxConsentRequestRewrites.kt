package com.attriax.sdk.internal.consent

import com.attriax.sdk.internal.request.AttriaxApiRequest

/**
 * Pure body-map rewrites applied to queued requests when consent resolves
 * (PARITY §5, row C5). Mirrors the Flutter reference `consent_request_rewrites.dart`
 * (`attriaxAnonymizeRequestForConsent` / `attriaxIdentifyRequestForConsentNotRequired`).
 *
 * The engine models a request as a `kind` + JSON body map, so identity handling
 * reduces to adding/removing the `deviceId`/`deviceIdSource` keys — no per-DTO
 * reconstruction is needed. Both functions are pure and return a NEW request
 * (the body map is copied), so the queue rewrite stays free of aliasing bugs.
 */
object AttriaxConsentRequestRewrites {

    /**
     * ANONYMIZE (pass 2): strip the device identity from a request so it is sent
     * without device-linked identity. Applied to capture-but-anonymous requests.
     * Requests are stripped unconditionally (matching the Flutter anonymize
     * rewrite, which rebuilds the payload without deviceId/deviceIdSource).
     */
    fun anonymize(request: AttriaxApiRequest): AttriaxApiRequest {
        if (!hasIdentity(request)) return request
        val body = LinkedHashMap(request.body)
        body.remove(AttriaxApiRequest.FIELD_DEVICE_ID)
        body.remove(AttriaxApiRequest.FIELD_DEVICE_ID_SOURCE)
        return request.copy(body = body)
    }

    /**
     * IDENTIFY (pass 1): attach the device identity to an anonymous request now
     * that identified tracking is allowed. Only requests that DO NOT already carry
     * a deviceId are rewritten (mirrors the Flutter `when payload.deviceId == null`
     * guards); already-identified requests are returned unchanged so the caller's
     * rewrite count reflects only the ones actually changed.
     */
    fun identify(
        request: AttriaxApiRequest,
        deviceId: String,
        deviceIdSource: String,
    ): AttriaxApiRequest? {
        if (!supportsIdentity(request.kind)) return null
        if (request.body[AttriaxApiRequest.FIELD_DEVICE_ID] != null) return null
        val body = LinkedHashMap(request.body)
        body[AttriaxApiRequest.FIELD_DEVICE_ID] = deviceId
        body[AttriaxApiRequest.FIELD_DEVICE_ID_SOURCE] = deviceIdSource
        return request.copy(body = body)
    }

    private fun hasIdentity(request: AttriaxApiRequest): Boolean =
        request.body[AttriaxApiRequest.FIELD_DEVICE_ID] != null ||
            request.body[AttriaxApiRequest.FIELD_DEVICE_ID_SOURCE] != null

    /** Request kinds that carry an optional device identity eligible for rewrite. */
    private fun supportsIdentity(kind: String): Boolean = when (kind) {
        AttriaxApiRequest.KIND_TRACK_EVENT,
        AttriaxApiRequest.KIND_TRACK_CRASH,
        AttriaxApiRequest.KIND_TRACK_SESSION,
        AttriaxApiRequest.KIND_RESOLVE_DEEP_LINK,
        AttriaxApiRequest.KIND_TRACK_NOTIFICATION -> true
        else -> false
    }
}
