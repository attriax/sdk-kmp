package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxIso8601

/**
 * Typed result of [Attriax.validateReceipt] (PARITY §4). Mirrors the Flutter
 * reference `AttriaxRevenueReceiptValidationResult`
 * (attriax_flutter_platform_interface types_links.dart:327-376) field-for-field.
 *
 * Timestamp representation is the ONE deliberate shape difference: Flutter exposes
 * `acceptedAt` / `expiresAt` as `DateTime?`; the KMP core has no date type on the
 * public surface, so these are epoch-millis `Long?` (parsed from the wire ISO-8601
 * strings via [AttriaxIso8601.parseUtcMillisOrNull], the same soft parse Flutter's
 * `DateTime.tryParse` uses) and carry the `Ms` suffix used elsewhere in the core.
 * Every other field name and null semantic matches Flutter exactly.
 */
data class AttriaxRevenueReceiptValidationResult(
    val validationId: String,
    val status: AttriaxRevenueReceiptValidationStatus,
    /** Sanitized receipt payload the backend echoes back. Defaults to empty (never null), matching Flutter. */
    val publicReceipt: Map<String, Any?> = emptyMap(),
    val requestVersion: String? = null,
    val acceptedAtMs: Long? = null,
    val provider: String? = null,
    val environment: String? = null,
    val transactionId: String? = null,
    val originalTransactionId: String? = null,
    val productId: String? = null,
    val failureReason: String? = null,
    val expiresAtMs: Long? = null,
    val providerResult: Map<String, Any?>? = null,
) {
    companion object {
        /**
         * Parse the (envelope-unwrapped) receipt-validation response `data` map into
         * the typed result. Lenient like the Flutter `fromJson`: an unknown/absent
         * `status` collapses to [AttriaxRevenueReceiptValidationStatus.REJECTED] and a
         * missing `publicReceipt` becomes an empty map. `validationId` is REQUIRED in
         * Flutter (throws on absence); the KMP core stays null-safe and defaults it to
         * an empty string instead of throwing, so a malformed response never crashes
         * the purchase flow. A null/non-map [decoded] yields a rejected empty result.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromResponse(decoded: Any?): AttriaxRevenueReceiptValidationResult {
            val data = decoded as? Map<String, Any?>
                ?: return AttriaxRevenueReceiptValidationResult(
                    validationId = "",
                    status = AttriaxRevenueReceiptValidationStatus.REJECTED,
                )
            return AttriaxRevenueReceiptValidationResult(
                validationId = (data["validationId"] as? String) ?: "",
                status = AttriaxRevenueReceiptValidationStatus.fromWire(data["status"] as? String),
                publicReceipt = (data["publicReceipt"] as? Map<String, Any?>) ?: emptyMap(),
                requestVersion = data["requestVersion"] as? String,
                acceptedAtMs = (data["acceptedAt"] as? String)?.let { AttriaxIso8601.parseUtcMillisOrNull(it) },
                provider = data["provider"] as? String,
                environment = data["environment"] as? String,
                transactionId = data["transactionId"] as? String,
                originalTransactionId = data["originalTransactionId"] as? String,
                productId = data["productId"] as? String,
                failureReason = data["failureReason"] as? String,
                expiresAtMs = (data["expiresAt"] as? String)?.let { AttriaxIso8601.parseUtcMillisOrNull(it) },
                providerResult = data["providerResult"] as? Map<String, Any?>,
            )
        }
    }
}

/**
 * Outcome of a receipt validation (PARITY — Flutter
 * `AttriaxRevenueReceiptValidationStatus`, types.dart:67-74). Wire-string mapping
 * mirrors Flutter's `_parseRevenueReceiptValidationStatus`: `rejected` is the
 * default for `rejected` and any unknown/absent value.
 */
enum class AttriaxRevenueReceiptValidationStatus {
    VERIFIED,
    REJECTED,
    PENDING,
    UNCONFIGURED,
    PROVIDER_ERROR,
    PASSTHROUGH;

    companion object {
        fun fromWire(value: String?): AttriaxRevenueReceiptValidationStatus = when (value?.trim()) {
            "verified" -> VERIFIED
            "pending" -> PENDING
            "unconfigured" -> UNCONFIGURED
            "provider_error" -> PROVIDER_ERROR
            "passthrough" -> PASSTHROUGH
            // 'rejected' + any unknown/absent value (Flutter's default branch).
            else -> REJECTED
        }
    }
}
