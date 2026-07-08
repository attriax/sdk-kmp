package com.attriax.sdk

import com.attriax.sdk.internal.deeplink.AttriaxUri

/**
 * Public deep-link value types (PARITY §6, rows DL1–DL4). Mirrors the Flutter
 * reference `types_deep_link_lifecycle.dart` + `types.dart` enums.
 */

/** What caused a deep-link event to be emitted (Flutter `AttriaxDeepLinkTrigger`). */
enum class AttriaxDeepLinkTrigger {
    /** The app launched from a fully stopped state because of this link. */
    COLD_START,

    /** The link arrived while the app was already running. */
    FOREGROUND,

    /** The link click happened before install and resolved on first launch. */
    DEFERRED,
}

/** Backend resolution outcome for a deep link (row DL4). */
enum class AttriaxDeepLinkResolutionStatus {
    MATCHED,
    UNMATCHED,
    INVALID;

    companion object {
        /** Map the wire `status` string (`matched|unmatched|invalid`) to the enum. */
        fun fromWire(value: String?): AttriaxDeepLinkResolutionStatus = when (value?.trim()?.lowercase()) {
            "matched" -> MATCHED
            "unmatched" -> UNMATCHED
            "invalid" -> INVALID
            // An unknown/absent status is treated as unmatched (safe default: the
            // link is not treated as a confirmed match).
            else -> UNMATCHED
        }
    }
}

/** How the SDK should open a resolved browser URL, when the backend returns one. */
enum class AttriaxResolvedUrlOpenMode {
    IN_APP,
    EXTERNAL,
    UNKNOWN;

    companion object {
        fun fromWire(value: String?): AttriaxResolvedUrlOpenMode = when (value?.trim()?.lowercase()) {
            "in_app", "inapp" -> IN_APP
            "external" -> EXTERNAL
            else -> UNKNOWN
        }
    }
}

/** Optional browser action carried by a resolution (backend `browserAction`). */
data class AttriaxBrowserAction(
    val url: String,
    val openMode: AttriaxResolvedUrlOpenMode,
)

/** A raw deep-link input captured from native platform intents (row DL1). */
data class AttriaxRawDeepLinkEvent(
    val uri: AttriaxUri,
    val receivedAtMs: Long,
    val isInitial: Boolean,
)

/** A handled deep-link event emitted to observers after resolution (rows DL2/DL3). */
data class AttriaxDeepLinkEvent(
    val uri: AttriaxUri,
    val clickedAtMs: Long,
    val consumedAtMs: Long,
    val found: Boolean,
    val trigger: AttriaxDeepLinkTrigger,
    val isAttriaxSubDomain: Boolean,
    val status: AttriaxDeepLinkResolutionStatus,
    val rawEvent: AttriaxRawDeepLinkEvent? = null,
    val data: Map<String, String>? = null,
    val utm: Map<String, String>? = null,
    val browserAction: AttriaxBrowserAction? = null,
) {
    val isDeferred: Boolean get() = trigger == AttriaxDeepLinkTrigger.DEFERRED
    val isColdStart: Boolean get() = trigger == AttriaxDeepLinkTrigger.COLD_START
    val isForeground: Boolean get() = trigger == AttriaxDeepLinkTrigger.FOREGROUND
}

/** Decoded backend deep-link resolution response (`/deep-links/resolve` data). */
data class AttriaxDeepLinkResolutionResult(
    val matched: Boolean,
    val status: AttriaxDeepLinkResolutionStatus,
    val isFirstLaunch: Boolean,
    val reason: String? = null,
    val consumedAtMs: Long? = null,
    val path: String? = null,
    val uri: String? = null,
    val data: Map<String, String>? = null,
    val utm: Map<String, String>? = null,
    val browserAction: AttriaxBrowserAction? = null,
)

/** Redirect defaults passed to [AttriaxDeepLinks.createDynamicLink]. */
data class AttriaxDynamicLinkRedirects(
    val ios: Boolean? = null,
    val android: Boolean? = null,
)

/** Open Graph social preview passed to [AttriaxDeepLinks.createDynamicLink]. */
data class AttriaxDynamicLinkSocialPreview(
    val title: String? = null,
    val description: String? = null,
)

/** UTM parameters passed to [AttriaxDeepLinks.createDynamicLink]. */
data class AttriaxDynamicLinkUtms(
    val source: String? = null,
    val medium: String? = null,
    val campaign: String? = null,
    val term: String? = null,
    val content: String? = null,
)

/** The persisted dynamic-link record returned by the backend. */
data class AttriaxDynamicLinkRecord(
    val id: String,
    val path: String,
    val shortUrl: String,
    val name: String? = null,
    val destinationUrl: String? = null,
    val group: String? = null,
    val prefix: String? = null,
    val data: Map<String, Any?>? = null,
)

/** Result of [AttriaxDeepLinks.createDynamicLink]. */
data class AttriaxCreateDynamicLinkResult(
    val shortUrl: String,
    val record: AttriaxDynamicLinkRecord,
)

/** Observer for handled deep-link events (row DL1 listener pattern). */
fun interface AttriaxDeepLinkListener {
    fun onDeepLink(event: AttriaxDeepLinkEvent)
}

/** Observer for raw (pre-resolution) deep-link inputs. */
fun interface AttriaxRawDeepLinkListener {
    fun onRawDeepLink(event: AttriaxRawDeepLinkEvent)
}
