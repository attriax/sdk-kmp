package com.attriax.sdk.internal.referrer

import com.attriax.sdk.AttributionType
import com.attriax.sdk.AttriaxDeepLinkEvent
import com.attriax.sdk.AttriaxDeepLinkReferrerDetails
import com.attriax.sdk.AttriaxInstallReferrerDetails
import com.attriax.sdk.internal.deeplink.AttriaxUri

/**
 * Pure mappers between the engine's internal shapes and the public referrer value
 * types. Mirrors the Flutter reference `attriax_deep_link_referrer_mapper.dart`
 * (deep-link) and `AttriaxInstallReferrerDetails.fromJson` (install referrer). No
 * framework types, so fully unit-testable off-device.
 */
internal object AttriaxReferrerMapper {

    /**
     * Map a handled [AttriaxDeepLinkEvent] to a public
     * [AttriaxDeepLinkReferrerDetails] (Flutter
     * `attriaxDeepLinkReferrerDetailsFromEvent`). `receivedAt` prefers the raw
     * event's receipt time, falling back to `clickedAt`.
     */
    fun deepLinkReferrerFromEvent(event: AttriaxDeepLinkEvent): AttriaxDeepLinkReferrerDetails =
        AttriaxDeepLinkReferrerDetails(
            uri = event.uri,
            receivedAtMs = event.rawEvent?.receivedAtMs ?: event.clickedAtMs,
            clickedAtMs = event.clickedAtMs,
            consumedAtMs = event.consumedAtMs,
            trigger = event.trigger,
            isAttriaxDomain = event.isAttriaxSubDomain,
            found = event.found,
            data = event.data,
            utm = event.utm,
            browserAction = event.browserAction,
        )

    /**
     * Decode a persisted app-open attribution object into an
     * [AttriaxInstallReferrerDetails] (Flutter `AttriaxInstallReferrerDetails.fromJson`,
     * `types_links.dart:116`). Field names + null semantics match the wire exactly;
     * `attributionType` defaults to organic and `precision` to `0.0`.
     */
    fun installReferrerDetailsFromMap(map: Map<*, *>): AttriaxInstallReferrerDetails {
        val deepLinkUrl = asString(map["deepLinkUrl"])
        val deepLinkUriRaw = asString(map["deepLinkUri"]) ?: deepLinkUrl
        return AttriaxInstallReferrerDetails(
            rawPlatformInstallReferrer = asString(map["rawPlatformInstallReferrer"]),
            source = asString(map["source"]),
            medium = asString(map["medium"]),
            campaign = asString(map["campaign"]),
            term = asString(map["term"]),
            content = asString(map["content"]),
            adNetwork = asString(map["adNetwork"]),
            adClickId = asString(map["adClickId"]),
            attributionType = AttributionType.fromWire(asString(map["attributionType"])),
            deepLinkUrl = deepLinkUrl,
            deepLinkUri = AttriaxUri.parse(deepLinkUriRaw),
            deepLinkData = asStringMap(map["deepLinkData"]),
            registeredAt = asString(map["registeredAt"]),
            installBeginTimestampSeconds = asLong(map["installBeginTimestampSeconds"]),
            referrerClickTimestampSeconds = asLong(map["referrerClickTimestampSeconds"]),
            googlePlayInstantParam = asBool(map["googlePlayInstantParam"]),
            precision = asDouble(map["precision"]) ?: 0.0,
        )
    }

    private fun asString(value: Any?): String? = value as? String

    private fun asBool(value: Any?): Boolean? = value as? Boolean

    private fun asLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun asDouble(value: Any?): Double? = when (value) {
        is Double -> value
        is Long -> value.toDouble()
        is Int -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun asStringMap(value: Any?): Map<String, String>? {
        val map = value as? Map<*, *> ?: return null
        if (map.isEmpty()) return null
        val out = LinkedHashMap<String, String>()
        for ((k, v) in map) {
            if (k == null) continue
            out[k.toString()] = v?.toString() ?: ""
        }
        return out.ifEmpty { null }
    }
}
