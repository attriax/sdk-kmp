package com.attriax.sdk

import com.attriax.sdk.internal.deeplink.AttriaxUri

/**
 * Public referrer value types. Mirrors the Flutter
 * reference `AttriaxInstallReferrerDetails` (`types_links.dart`) and
 * `AttriaxDeepLinkReferrerDetails` (`types_deep_link_lifecycle.dart`), which back
 * the `attriax.referrer` getters.
 */

/**
 * How an install was attributed (Flutter `AttributionType`,
 * `types.dart:12-24`). The wire value is the lowercase enum name; an
 * unknown/absent value maps to [ORGANIC] (matching Flutter `_parseAttributionType`).
 */
enum class AttributionType {
    /** Attribution derived from platform install-referrer data. */
    REFERRER,

    /** Attribution derived from probabilistic fingerprint matching. */
    FINGERPRINT,

    /** Attribution derived from external provider resolutions. */
    EXTERNAL,

    /** Attribution assigned when no attributable source was found. */
    ORGANIC;

    companion object {
        /** Map the wire `attributionType` string to the enum (defaults to [ORGANIC]). */
        fun fromWire(value: String?): AttributionType = when (value?.trim()?.lowercase()) {
            "referrer" -> REFERRER
            "fingerprint" -> FINGERPRINT
            "external" -> EXTERNAL
            else -> ORGANIC
        }
    }
}

/**
 * The install-attribution snapshot resolved for an installation (Flutter
 * `AttriaxInstallReferrerDetails`, `types_links.dart:95`). Returned by
 * [AttriaxReferrer.getOriginalInstallReferrer] / [AttriaxReferrer.getReinstallReferrer]
 * and sourced from the persisted app-open attribution response.
 *
 * All fields except [attributionType] and [precision] are nullable, mirroring the
 * Flutter reference. [attributionType] defaults to [AttributionType.ORGANIC] and
 * [precision] to `0.0` when the wire omits them.
 */
data class AttriaxInstallReferrerDetails(
    val rawPlatformInstallReferrer: String? = null,
    val source: String? = null,
    val medium: String? = null,
    val campaign: String? = null,
    val term: String? = null,
    val content: String? = null,
    val adNetwork: String? = null,
    val adClickId: String? = null,
    val attributionType: AttributionType = AttributionType.ORGANIC,
    /** Backend deep-link URL string, when the attribution carried one. */
    val deepLinkUrl: String? = null,
    /** Parsed [deepLinkUrl] (Flutter `deepLinkUri`), when parseable. */
    val deepLinkUri: AttriaxUri? = null,
    val deepLinkData: Map<String, String>? = null,
    /** ISO-8601 timestamp the attribution was registered, when present. */
    val registeredAt: String? = null,
    val installBeginTimestampSeconds: Long? = null,
    val referrerClickTimestampSeconds: Long? = null,
    val googlePlayInstantParam: Boolean? = null,
    val precision: Double = 0.0,
) {
    /**
     * The non-empty UTM parameters carried by this attribution (Flutter `utm`
     * getter), or `null` when none of source/medium/campaign/term/content are set.
     */
    val utm: Map<String, String>?
        get() {
            val map = LinkedHashMap<String, String>()
            source?.let { map["source"] = it }
            medium?.let { map["medium"] = it }
            campaign?.let { map["campaign"] = it }
            term?.let { map["term"] = it }
            content?.let { map["content"] = it }
            return map.ifEmpty { null }
        }
}

/**
 * A deep-link referrer snapshot (Flutter `AttriaxDeepLinkReferrerDetails`,
 * `types_deep_link_lifecycle.dart:29`). Returned by
 * [AttriaxReferrer.getSessionReferrer] / [AttriaxReferrer.getLatestDeepLinkReferrer],
 * derived from a handled [AttriaxDeepLinkEvent].
 *
 * NOTE vs Flutter: the native [AttriaxDeepLinkEvent] does not carry a
 * `handledBySdk` flag, so that Flutter field has no native source and is omitted
 * here (see the parity note in the referrer coordinator).
 */
data class AttriaxDeepLinkReferrerDetails(
    val uri: AttriaxUri,
    val receivedAtMs: Long,
    val clickedAtMs: Long,
    val consumedAtMs: Long,
    val trigger: AttriaxDeepLinkTrigger,
    val isAttriaxDomain: Boolean,
    val found: Boolean,
    val data: Map<String, String>? = null,
    val utm: Map<String, String>? = null,
    val browserAction: AttriaxBrowserAction? = null,
)
