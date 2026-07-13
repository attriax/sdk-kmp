package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxNotificationEventSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure lowering logic — currency validation, refund
 * negation, and notification-source inference, all framework-free.
 */
class AttriaxRevenueTest {

    // -------- E3: currency validation --------

    @Test
    fun validCurrencyPassesThroughUppercased() {
        val r = AttriaxRevenue.normalizeRevenueCurrency(9.99, "usd")
        assertEquals(9.99, r.revenue, 0.0)
        assertEquals("USD", r.currency)
    }

    @Test
    fun validCurrencyTrimsWhitespace() {
        val r = AttriaxRevenue.normalizeRevenueCurrency(4.0, "  eur ")
        assertEquals(4.0, r.revenue, 0.0)
        assertEquals("EUR", r.currency)
    }

    @Test
    fun invalidCurrencyDefaultsToZeroUsd() {
        val r = AttriaxRevenue.normalizeRevenueCurrency(9.99, "US")
        assertEquals(0.0, r.revenue, 0.0)
        assertEquals("USD", r.currency)
    }

    @Test
    fun blankCurrencyDefaultsToZeroUsd() {
        val r = AttriaxRevenue.normalizeRevenueCurrency(9.99, "   ")
        assertEquals(0.0, r.revenue, 0.0)
        assertEquals("USD", r.currency)
    }

    @Test
    fun fourLetterCurrencyIsInvalid() {
        assertFalse(AttriaxRevenue.isValidCurrency("USDD"))
        assertTrue(AttriaxRevenue.isValidCurrency("gbp"))
        assertFalse(AttriaxRevenue.isValidCurrency("12A"))
    }

    // -------- E2: refund negation --------

    @Test
    fun refundNegatesPositiveRevenue() {
        assertEquals(-9.99, AttriaxRevenue.refundRevenue(9.99), 0.0)
    }

    @Test
    fun refundOfAlreadyNegativeStaysNegative() {
        assertEquals(-9.99, AttriaxRevenue.refundRevenue(-9.99), 0.0)
    }

    @Test
    fun refundOfZeroIsZeroNoSignedZero() {
        val r = AttriaxRevenue.refundRevenue(0.0)
        assertEquals(0.0, r, 0.0)
        // Guard against -0.0 leaking to the wire.
        assertFalse(1.0 / r < 0)
    }

    // -------- E6: notification source inference --------

    @Test
    fun apsPayloadInfersApns() {
        val src = AttriaxRevenue.inferNotificationSource(mapOf("aps" to mapOf("alert" to "hi")))
        assertEquals(AttriaxNotificationEventSource.APNS, src)
    }

    @Test
    fun googlePrefixInfersFcm() {
        val src = AttriaxRevenue.inferNotificationSource(mapOf("google.message_id" to "123"))
        assertEquals(AttriaxNotificationEventSource.FCM, src)
    }

    @Test
    fun gcmPrefixInfersFcm() {
        val src = AttriaxRevenue.inferNotificationSource(mapOf("gcm.notification.title" to "x"))
        assertEquals(AttriaxNotificationEventSource.FCM, src)
    }

    @Test
    fun apsWinsOverFcmKeysWhenBothPresent() {
        val src = AttriaxRevenue.inferNotificationSource(
            mapOf("aps" to emptyMap<String, Any?>(), "google.message_id" to "1"),
        )
        assertEquals(AttriaxNotificationEventSource.APNS, src)
    }

    @Test
    fun undecidablePayloadReturnsNull() {
        assertNull(AttriaxRevenue.inferNotificationSource(mapOf("custom" to "value")))
        assertNull(AttriaxRevenue.inferNotificationSource(emptyMap()))
        assertNull(AttriaxRevenue.inferNotificationSource(null))
    }

    // -------- metadata merge --------

    @Test
    fun mergeStoresPayloadUnderPayloadKey() {
        val merged = AttriaxRevenue.mergeNotificationMetadata(
            metadata = mapOf("campaign" to "spring"),
            payload = mapOf("aps" to "x"),
        )!!
        assertEquals(mapOf("aps" to "x"), merged["payload"])
        assertEquals("spring", merged["campaign"])
    }

    @Test
    fun mergeReturnsMetadataUntouchedWhenNoPayload() {
        val meta = mapOf("a" to 1)
        assertEquals(meta, AttriaxRevenue.mergeNotificationMetadata(metadata = meta, payload = null))
        assertNull(AttriaxRevenue.mergeNotificationMetadata(metadata = null, payload = emptyMap()))
    }
}
