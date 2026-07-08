package com.attriax.sdk.internal.deeplink

import com.attriax.sdk.AttriaxDeepLinkEvent
import com.attriax.sdk.AttriaxDeepLinkResolutionStatus
import com.attriax.sdk.AttriaxDeepLinkTrigger

/**
 * Pure deferred deep-link recovery from the app-open RESPONSE (PARITY §6, row DL3).
 * Mirrors the Flutter reference `attriax_deep_link_resolver.dart` (buildDeferredUri /
 * buildDeferredResolution) + `attriax_deep_link_manager.dart` (handleDeferredAppOpen).
 *
 * The open response envelope is `{ data: { ... } }`; the transport has already
 * unwrapped it, so [recover] receives the inner `data` map. Source PREFERENCE
 * order for the deferred URI is:
 *
 *   1. `data.deepLink.uri` (falls back to a path-derived URI)
 *   2. `data.reinstallReferrer.deepLinkUri`
 *   3. `data.installReferrer.deepLinkUri`
 *
 * A response with `installState == "appDataClear"` is skipped entirely (a data
 * clear is not a genuine deferred-conversion signal). `found` is true only when a
 * concrete `deepLink` object is present (matching Flutter `found: result.deepLink != null`).
 */
object AttriaxDeepLinkDeferredRecovery {

    const val INSTALL_STATE_APP_DATA_CLEAR = "appDataClear"

    /**
     * Attempt to recover a deferred deep-link event from an unwrapped app-open
     * response [data] map. Returns null when there is nothing to emit (no data,
     * appDataClear, or no recoverable URI/deepLink at all).
     *
     * @param fallbackTimeMs used for clicked/consumed timestamps when the response
     *   omits them.
     */
    fun recover(data: Map<String, Any?>?, fallbackTimeMs: Long): AttriaxDeepLinkEvent? {
        if (data == null) return null
        if ((data["installState"] as? String) == INSTALL_STATE_APP_DATA_CLEAR) return null

        val deepLink = data["deepLink"] as? Map<*, *>
        val reinstall = data["reinstallReferrer"] as? Map<*, *>
        val install = data["installReferrer"] as? Map<*, *>

        val uriString = (deepLink?.get("uri") as? String)
            ?: (deepLink?.get("path") as? String)?.let { pathAsUri(AttriaxDeepLinkResolver.normalizeLinkPath(it)) }
            ?: (reinstall?.get("deepLinkUri") as? String)
            ?: (install?.get("deepLinkUri") as? String)
            ?: return null

        val uri = AttriaxUri.parse(uriString) ?: return null

        val clickedAt = timeOrNull(data["deepLinkClickedAt"])
            ?: timeOrNull(data["acceptedAt"])
            ?: fallbackTimeMs
        val consumedAt = timeOrNull(data["deepLinkConsumedAt"])
            ?: timeOrNull(data["acceptedAt"])
            ?: fallbackTimeMs

        return AttriaxDeepLinkEvent(
            uri = uri,
            clickedAtMs = clickedAt,
            consumedAtMs = consumedAt,
            // Deferred is a confirmed match only when a concrete deepLink is present.
            found = deepLink != null,
            trigger = AttriaxDeepLinkTrigger.DEFERRED,
            isAttriaxSubDomain = AttriaxDeepLinkResolver.isAttriaxDomain(uri),
            status = if (deepLink != null) {
                AttriaxDeepLinkResolutionStatus.MATCHED
            } else {
                AttriaxDeepLinkResolutionStatus.UNMATCHED
            },
            data = AttriaxDeepLinkResolver.stringMap(
                deepLink?.get("data")
                    ?: reinstall?.get("deepLinkData")
                    ?: install?.get("deepLinkData"),
            ),
            utm = AttriaxDeepLinkResolver.stringMap(
                deepLink?.get("utm")
                    ?: referrerUtm(reinstall)
                    ?: referrerUtm(install),
            ),
        )
    }

    /**
     * The install-referrer result carries UTM fields flat (source/medium/campaign/
     * term/content); collect the present ones into a map for the event `utm`.
     */
    private fun referrerUtm(referrer: Map<*, *>?): Map<String, String>? {
        if (referrer == null) return null
        val utm = LinkedHashMap<String, String>()
        for (key in UTM_KEYS) {
            (referrer[key] as? String)?.let { utm[key] = it }
        }
        return utm.ifEmpty { null }
    }

    private fun pathAsUri(normalizedPath: String?): String =
        if (normalizedPath == null) "/" else "/$normalizedPath"

    /** Deferred timestamps arrive as ISO-8601 strings; we only need a monotone ordering. */
    private fun timeOrNull(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        else -> null
    }

    private val UTM_KEYS = listOf("source", "medium", "campaign", "term", "content")
}
