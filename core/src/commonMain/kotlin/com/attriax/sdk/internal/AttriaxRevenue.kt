package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxNotificationEventSource

/**
 * Pure, framework-free lowering helpers shared by the tracking surface.
 * Kept off the platform classpath so the reserved
 * event-name/param-key lowering, refund negation, currency validation, and
 * notification-source inference stay unit-testable like the slice-1 engine.
 */
object AttriaxRevenue {

    private val CURRENCY_REGEX = Regex("^[A-Z]{3}$")

    /** Normalized revenue amount + currency after validation. */
    data class NormalizedRevenue(val revenue: Double, val currency: String)

    /**
     * Validate [currency] against `^[A-Z]{3}$` (after trim+uppercase). On a valid
     * code the [revenue] passes through unchanged; otherwise revenue is defaulted
     * to `0` and the currency to `USD` (the caller emits a warning) —.
     */
    fun normalizeRevenueCurrency(revenue: Double, currency: String?): NormalizedRevenue {
        val normalizedCurrency = currency?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (normalizedCurrency != null && CURRENCY_REGEX.matches(normalizedCurrency)) {
            return NormalizedRevenue(revenue = revenue, currency = normalizedCurrency)
        }
        return NormalizedRevenue(revenue = 0.0, currency = "USD")
    }

    /** True when [currency] fails the `^[A-Z]{3}$` check (used to gate the warning). */
    fun isValidCurrency(currency: String?): Boolean {
        val normalized = currency?.trim()?.takeIf { it.isNotEmpty() }?.uppercase() ?: return false
        return CURRENCY_REGEX.matches(normalized)
    }

    /**
     * Refund revenue is the negated absolute value of the normalized revenue, with
     * `0` preserved as `0` (avoids a signed-zero) —.
     */
    fun refundRevenue(normalizedRevenue: Double): Double =
        if (normalizedRevenue == 0.0) 0.0 else -kotlin.math.abs(normalizedRevenue)

    /**
     * Best-effort inference of the delivery channel from a raw FCM/APNs payload
     * APNs payloads carry an `aps` envelope; FCM payloads carry a
     * `google.*` / `gcm.*` key. Returns `null` when undecidable so the server
     * falls back to `other`.
     */
    fun inferNotificationSource(payload: Map<String, Any?>?): AttriaxNotificationEventSource? {
        if (payload == null || payload.isEmpty()) return null
        if (payload.containsKey("aps")) return AttriaxNotificationEventSource.APNS
        val looksFcm = payload.keys.any { key ->
            key == "google.message_id" ||
                key == "gcm.message_id" ||
                key.startsWith("google.") ||
                key.startsWith("gcm.")
        }
        if (looksFcm) return AttriaxNotificationEventSource.FCM
        return null
    }

    /**
     * Preserve the raw FCM/APNs [payload] under a `payload` key inside the
     * notification metadata so attribution context survives to the server.
     * Explicit [metadata] entries take precedence over the payload key.
     */
    fun mergeNotificationMetadata(
        metadata: Map<String, Any?>?,
        payload: Map<String, Any?>?,
    ): Map<String, Any?>? {
        val hasPayload = payload != null && payload.isNotEmpty()
        val hasMetadata = metadata != null && metadata.isNotEmpty()
        if (!hasPayload && !hasMetadata) return metadata
        val merged = LinkedHashMap<String, Any?>()
        if (hasPayload) merged["payload"] = LinkedHashMap(payload)
        if (hasMetadata) merged.putAll(metadata!!)
        return merged
    }

    /** Trim [value] to null when blank (matches the Flutter `_trimOrNull`). */
    fun trimOrNull(value: String?): String? {
        val trimmed = value?.trim()
        return if (trimmed.isNullOrEmpty()) null else trimmed
    }
}
