package com.attriax.sdk.internal.deeplink

import com.attriax.sdk.AttriaxBrowserAction
import com.attriax.sdk.AttriaxDeepLinkEvent
import com.attriax.sdk.AttriaxDeepLinkResolutionResult
import com.attriax.sdk.AttriaxDeepLinkResolutionStatus
import com.attriax.sdk.AttriaxDeepLinkTrigger
import com.attriax.sdk.AttriaxRawDeepLinkEvent
import com.attriax.sdk.AttriaxResolvedUrlOpenMode

/**
 * Pure deep-link resolution helpers. Mirrors the
 * Flutter reference `attriax_deep_link_resolver.dart`. Framework-free so link-path
 * normalization, query-parameter metadata, status mapping, and deferred recovery
 * are all unit-testable without `android.content.Intent` / `android.net.Uri`.
 */
object AttriaxDeepLinkResolver {

    /**
     * Normalize a link path: trim, strip leading/trailing slashes, collapse to null
     * when empty. Mirrors the Dart `normalizeLinkPath` (leading `^/+`, trailing `/+$`).
     */
    fun normalizeLinkPath(path: String?): String? {
        if (path == null) return null
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val normalized = trimmed
            .replace(Regex("^/+"), "")
            .replace(Regex("/+$"), "")
        return normalized.ifEmpty { null }
    }

    /**
     * Extract the normalized link path from a URI (Dart `extractLinkPathFromUri`).
     * For http/https, prefer the path, else fall back to the host. For custom
     * schemes, join host + path when both present.
     */
    fun extractLinkPathFromUri(uri: AttriaxUri): String? {
        val normalizedPath = normalizeLinkPath(uri.path)
        if (uri.isScheme("http") || uri.isScheme("https")) {
            return normalizedPath ?: normalizeLinkPath(uri.host)
        }
        val normalizedHost = normalizeLinkPath(uri.host)
        if (normalizedHost != null && normalizedPath != null) {
            return normalizeLinkPath("$normalizedHost/$normalizedPath")
        }
        return normalizedPath ?: normalizedHost
    }

    /** Whether a URI targets an `*.attriax.com` subdomain (case-insensitive). */
    fun isAttriaxDomain(uri: AttriaxUri): Boolean {
        val host = uri.host?.trim()?.lowercase().orEmpty()
        return host.isNotEmpty() && host.endsWith(".attriax.com")
    }

    /**
     * Build the resolve-request metadata (Dart `_handleIncomingLink` metadata):
     * `isInitialLink` plus flattened `queryParameters` (multi-value preserved as
     * lists). Manual conversions may override/augment with caller metadata.
     */
    fun buildResolveMetadata(
        uri: AttriaxUri,
        isInitialLink: Boolean,
        extra: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        val metadata = LinkedHashMap<String, Any?>()
        extra?.let { metadata.putAll(it) }
        metadata["isInitialLink"] = isInitialLink
        metadata["queryParameters"] = uri.queryParametersAll
        return metadata
    }

    // -------- response decoding --------

    /** Decode a `/deep-links/resolve` response body map into a resolution result. */
    fun decodeResolution(data: Map<String, Any?>): AttriaxDeepLinkResolutionResult {
        val deepLink = (data["deepLink"] as? Map<*, *>)
        return AttriaxDeepLinkResolutionResult(
            matched = (data["matched"] as? Boolean) ?: false,
            status = AttriaxDeepLinkResolutionStatus.fromWire(data["status"] as? String),
            isFirstLaunch = (data["isFirstLaunch"] as? Boolean) ?: false,
            reason = data["reason"] as? String,
            consumedAtMs = null,
            path = deepLink?.get("path") as? String,
            uri = deepLink?.get("uri") as? String,
            data = stringMap(deepLink?.get("data")),
            utm = stringMap(deepLink?.get("utm")),
            browserAction = decodeBrowserAction(data["browserAction"]),
        )
    }

    fun decodeBrowserAction(value: Any?): AttriaxBrowserAction? {
        val map = value as? Map<*, *> ?: return null
        val url = map["url"] as? String ?: return null
        return AttriaxBrowserAction(
            url = url,
            openMode = AttriaxResolvedUrlOpenMode.fromWire(map["openMode"] as? String),
        )
    }

    /** Build the emitted event from a decoded resolution (Dart `buildResolution`). */
    fun buildResolution(
        result: AttriaxDeepLinkResolutionResult,
        clickedAtMs: Long,
        consumedAtMs: Long,
        trigger: AttriaxDeepLinkTrigger,
        fallbackUri: AttriaxUri,
        rawEvent: AttriaxRawDeepLinkEvent? = null,
        handledBySdk: Boolean = false,
    ): AttriaxDeepLinkEvent {
        // Prefer the backend canonical URI, then a URI derived from a normalized
        // path (only when a path was actually returned), else the original link.
        val canonical = AttriaxUri.parse(result.uri)
            ?: normalizeLinkPath(result.path)?.let { AttriaxUri.parse(pathAsUri(it)) }
            ?: fallbackUri
        return AttriaxDeepLinkEvent(
            uri = canonical,
            clickedAtMs = clickedAtMs,
            consumedAtMs = result.consumedAtMs ?: consumedAtMs,
            found = result.matched,
            trigger = trigger,
            isAttriaxSubDomain = isAttriaxDomain(fallbackUri),
            status = result.status,
            rawEvent = rawEvent,
            data = result.data,
            utm = result.utm,
            browserAction = result.browserAction,
            handledBySdk = handledBySdk,
        )
    }

    private fun pathAsUri(normalizedPath: String?): String =
        if (normalizedPath == null) "/" else "/$normalizedPath"

    @Suppress("UNCHECKED_CAST")
    fun stringMap(value: Any?): Map<String, String>? {
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
