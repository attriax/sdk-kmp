package com.attriax.sdk.internal.request

/** Wire endpoint paths (PARITY §8, row W1). */
object AttriaxEndpoints {
    const val OPEN = "/api/sdk/v1/open"
    const val EVENTS = "/api/sdk/v1/events"
    const val SESSIONS = "/api/sdk/v1/sessions"
    const val USERS = "/api/sdk/v1/users"
    const val NOTIFICATIONS = "/api/sdk/v1/notifications"
    const val CRASHES = "/api/sdk/v1/crashes"
    const val BATCH = "/api/sdk/v1/batch"
    const val DEEP_LINKS_RESOLVE = "/api/sdk/v1/deep-links/resolve"
    const val DYNAMIC_LINKS = "/api/sdk/v1/dynamic-links"
    const val UNINSTALL_TOKENS = "/api/sdk/v1/uninstall-tokens"
    const val CONSENT_CHECK = "/api/sdk/v1/consent/gdpr/check"
    const val CONSENT_UPSERT = "/api/sdk/v1/consent/gdpr"
    const val GDPR_ERASE = "/api/sdk/v1/privacy/gdpr/erase"
    const val RECEIPTS_VALIDATE = "/api/sdk/v1/revenue/receipts/validate"
    const val REVENUE_CONVERT = "/api/sdk/v1/revenue/convert-to-usd"
    const val CONFIG = "/api/sdk/v1/config"
    const val ATTESTATION_CHALLENGE = "/api/sdk/attestation/challenge"

    /** Apple Search Ads (AdServices) token capture — FROZEN CONTRACT (Epic 8.5). */
    const val ASA_TOKEN = "/api/sdk/v1/asa/token"
}

/**
 * An outbound SDK request modeled as a kind + a JSON body map (PARITY §7/§8).
 *
 * Deliberately data-driven rather than a class-per-endpoint: the engine only
 * needs the kind name (persisted queue tag / dispatch key), the HTTP path, and
 * the JSON body, plus a few boolean/identity queries for batching and retry.
 * Keeping the body as a plain `Map<String, Any?>` makes every derived
 * operation (queue serialization, batch hoist/strip, legacy normalization)
 * pure and unit-testable.
 *
 * @property kind persisted queue tag (e.g. `open`, `trackEvent`, `user`).
 * @property path wire endpoint for single-send.
 * @property body request payload as a JSON-encodable map.
 */
data class AttriaxApiRequest(
    val kind: String,
    val path: String,
    val body: Map<String, Any?>,
) {
    /** Whether this kind participates in batching, and only when identity is present. */
    val isBatchable: Boolean
        get() = when (kind) {
            KIND_TRACK_EVENT -> body[FIELD_DEVICE_ID] != null
            KIND_TRACK_SESSION -> body[FIELD_DEVICE_ID] != null
            KIND_USER -> true
            else -> false
        }

    /** True for the app-open request (hoisted to the front of every flush; row O2). */
    val isAppOpen: Boolean get() = kind == KIND_OPEN

    /** Deep-link resolves are exempt from the terminal-drop retry policy (row DL5/Q4). */
    val isTerminalDropExempt: Boolean get() = kind == KIND_RESOLVE_DEEP_LINK

    /** The batch item kind name for this request (`event`/`session`/`user`). */
    val batchKindName: String
        get() = when (kind) {
            KIND_TRACK_EVENT -> "event"
            KIND_TRACK_SESSION -> "session"
            KIND_USER -> "user"
            else -> throw IllegalArgumentException("Unsupported batch request kind: $kind")
        }

    companion object {
        const val KIND_OPEN = "open"
        const val KIND_TRACK_EVENT = "trackEvent"
        const val KIND_TRACK_SESSION = "trackSession"
        const val KIND_USER = "user"
        const val KIND_TRACK_NOTIFICATION = "trackNotification"
        const val KIND_TRACK_CRASH = "trackCrash"
        const val KIND_RESOLVE_DEEP_LINK = "resolveDeepLink"
        const val KIND_CREATE_DYNAMIC_LINK = "createDynamicLink"
        const val KIND_REGISTER_UNINSTALL_TOKEN = "registerUninstallToken"

        /** Legacy queue-kind alias for `user` (row FR1). */
        const val LEGACY_KIND_IDENTIFY = "identify"

        const val FIELD_PROJECT_TOKEN = "projectToken"
        const val FIELD_DEVICE_ID = "deviceId"
        const val FIELD_DEVICE_ID_SOURCE = "deviceIdSource"
        const val FIELD_LEGACY_APP_TOKEN = "appToken"
    }
}
