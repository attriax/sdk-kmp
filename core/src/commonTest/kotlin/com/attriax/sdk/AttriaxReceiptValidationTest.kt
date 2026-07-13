package com.attriax.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Typed receipt-validation result parsing (Flutter
 * `AttriaxRevenueReceiptValidationResult.fromJson`). The engine hands
 * [AttriaxRevenueReceiptValidationResult.fromResponse] the envelope-unwrapped `data`
 * map; every field maps by name, unknown/absent status collapses to REJECTED, and a
 * malformed response degrades gracefully (never throws).
 */
class AttriaxReceiptValidationTest {

    @Test
    fun mapsRepresentativeResponseToTypedFields() {
        val data = mapOf<String, Any?>(
            "requestVersion" to "v1",
            "acceptedAt" to "1994-11-06T08:49:37.700Z",
            "validationId" to "val_123",
            "status" to "verified",
            "provider" to "app_store",
            "environment" to "production",
            "transactionId" to "txn_1",
            "originalTransactionId" to "txn_0",
            "productId" to "com.acme.pro",
            "failureReason" to null,
            "expiresAt" to "1994-11-06T08:49:38.000Z",
            "providerResult" to mapOf("raw" to true),
            "publicReceipt" to mapOf("bundleId" to "com.acme"),
        )

        val result = AttriaxRevenueReceiptValidationResult.fromResponse(data)

        assertEquals("val_123", result.validationId)
        assertEquals(AttriaxRevenueReceiptValidationStatus.VERIFIED, result.status)
        assertEquals("v1", result.requestVersion)
        assertEquals(784_111_777_700L, result.acceptedAtMs)
        assertEquals("app_store", result.provider)
        assertEquals("production", result.environment)
        assertEquals("txn_1", result.transactionId)
        assertEquals("txn_0", result.originalTransactionId)
        assertEquals("com.acme.pro", result.productId)
        assertNull(result.failureReason)
        assertEquals(784_111_778_000L, result.expiresAtMs)
        assertEquals(true, result.providerResult?.get("raw"))
        assertEquals("com.acme", result.publicReceipt["bundleId"])
    }

    @Test
    fun mapsWireStatusStrings() {
        fun status(s: String?) =
            AttriaxRevenueReceiptValidationResult.fromResponse(mapOf("status" to s)).status
        assertEquals(AttriaxRevenueReceiptValidationStatus.VERIFIED, status("verified"))
        assertEquals(AttriaxRevenueReceiptValidationStatus.PENDING, status("pending"))
        assertEquals(AttriaxRevenueReceiptValidationStatus.UNCONFIGURED, status("unconfigured"))
        assertEquals(AttriaxRevenueReceiptValidationStatus.PROVIDER_ERROR, status("provider_error"))
        assertEquals(AttriaxRevenueReceiptValidationStatus.PASSTHROUGH, status("passthrough"))
        assertEquals(AttriaxRevenueReceiptValidationStatus.REJECTED, status("rejected"))
        // Unknown/absent → rejected (Flutter's default branch).
        assertEquals(AttriaxRevenueReceiptValidationStatus.REJECTED, status("weird"))
        assertEquals(AttriaxRevenueReceiptValidationStatus.REJECTED, status(null))
    }

    @Test
    fun malformedResponseDegradesGracefully() {
        // Non-map / null → rejected empty result, no throw.
        val fromNull = AttriaxRevenueReceiptValidationResult.fromResponse(null)
        assertEquals("", fromNull.validationId)
        assertEquals(AttriaxRevenueReceiptValidationStatus.REJECTED, fromNull.status)
        assertTrue(fromNull.publicReceipt.isEmpty())

        val fromString = AttriaxRevenueReceiptValidationResult.fromResponse("garbage")
        assertEquals(AttriaxRevenueReceiptValidationStatus.REJECTED, fromString.status)

        // Missing validationId + publicReceipt → defaults ("" and empty map).
        val partial = AttriaxRevenueReceiptValidationResult.fromResponse(mapOf("status" to "verified"))
        assertEquals("", partial.validationId)
        assertEquals(AttriaxRevenueReceiptValidationStatus.VERIFIED, partial.status)
        assertTrue(partial.publicReceipt.isEmpty())
        assertNull(partial.acceptedAtMs)
    }
}
