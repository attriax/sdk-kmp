package com.attriax.sdk.internal.request

import com.attriax.sdk.internal.AttriaxContextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire-shape acceptance for the slice-2 endpoints — field names + nesting must
 * match the api DTOs exactly (whitelist validation rejects unknown fields).
 * Crash/notification/uninstall/receipt are all FLAT (only open nests).
 */
class AttriaxTrackingRequestBuildersTest {

    private val ctx = AttriaxContextSnapshot(
        packageName = "com.x",
        appVersion = "1.2.3",
        appBuildNumber = "45",
        deviceModel = "Pixel",
        deviceManufacturer = "Google",
        osVersion = "14",
        deviceTimezone = "UTC",
        deviceLocale = "en-US",
    )

    // -------- crash (SdkCrashDto — FLAT + full context inline) --------

    @Test
    fun crashIsFlatWithRequiredFieldsAndInlineContext() {
        val crash = AttriaxRequestBuilders.buildCrash(
            projectToken = "t",
            context = ctx,
            deviceId = "d",
            deviceIdSource = "android_ssaid",
            source = "manual",
            isFatal = true,
            exceptionType = "java.lang.IllegalStateException",
            message = "boom",
            stackTrace = "at Foo.bar",
            isFirstLaunch = false,
            clientOccurredAtIso = "2026-01-01T00:00:00.000Z",
            reason = "unexpected",
            sessionId = null,
            sessionRelativeTimeMs = null,
            metadata = mapOf("k" to "v"),
        )
        val body = crash.body
        assertEquals(AttriaxEndpoints.CRASHES, crash.path)
        assertEquals("t", body["projectToken"])
        assertEquals("d", body["deviceId"])
        assertEquals("android_ssaid", body["deviceIdSource"])
        assertEquals("manual", body["source"])
        assertEquals("2026-01-01T00:00:00.000Z", body["clientOccurredAt"])
        assertEquals("android", body["platform"])
        assertEquals(true, body["isFatal"])
        assertEquals("java.lang.IllegalStateException", body["exceptionType"])
        assertEquals("boom", body["message"])
        assertEquals("at Foo.bar", body["stackTrace"])
        assertEquals(false, body["isFirstLaunch"])
        assertEquals("unexpected", body["reason"])
        // Context is FLAT on this DTO (not nested under app/device/sdk).
        assertEquals("en-US", body["locale"])
        assertEquals("1.2.3", body["appVersion"])
        assertEquals("45", body["appBuildNumber"])
        assertEquals("com.x", body["appPackageName"])
        assertEquals("v1", body["sdkApiVersion"])
        assertEquals("0.6.0", body["sdkPackageVersion"])
        assertEquals(mapOf("k" to "v"), body["metadata"])
        assertFalse(body.containsKey("app"))
        assertFalse(body.containsKey("device"))
        assertFalse(body.containsKey("sdk"))
    }

    // -------- notification (SdkNotificationDto — FLAT) --------

    @Test
    fun notificationMatchesDtoFieldNames() {
        val notif = AttriaxRequestBuilders.buildNotification(
            projectToken = "t",
            platform = "android",
            type = "opened",
            notificationId = "n-1",
            deviceId = "d",
            deviceIdSource = "android_ssaid",
            linkId = null,
            campaignId = null,
            title = "Hello",
            source = "fcm",
            sessionId = null,
            occurredAtIso = "2026-01-01T00:00:00.000Z",
            metadata = mapOf("payload" to mapOf("google.message_id" to "1")),
        )
        val body = notif.body
        assertEquals(AttriaxEndpoints.NOTIFICATIONS, notif.path)
        assertEquals("opened", body["type"])
        assertEquals("n-1", body["notificationId"])
        assertEquals("android", body["platform"])
        assertEquals("fcm", body["source"])
        assertEquals("Hello", body["title"])
        assertEquals("2026-01-01T00:00:00.000Z", body["occurredAt"])
        assertEquals(mapOf("payload" to mapOf("google.message_id" to "1")), body["metadata"])
        // Not on this DTO — would be rejected.
        assertFalse(body.containsKey("clientOccurredAt"))
        assertFalse(body.containsKey("sessionRelativeTimeMs"))
        // Null optionals omitted entirely.
        assertFalse(body.containsKey("linkId"))
        assertFalse(body.containsKey("campaignId"))
    }

    // -------- user / identify (SdkUserDto) --------

    @Test
    fun userMapsIdentityAndClearFlags() {
        val user = AttriaxRequestBuilders.buildUser(
            projectToken = "t",
            externalUserId = null,
            externalUserName = null,
            properties = null,
            deviceId = "d",
            deviceIdSource = "android_ssaid",
            clearExternalUser = true,
            clearAllProperties = true,
        )
        val body = user.body
        assertEquals("d", body["deviceId"])
        assertEquals(true, body["clearExternalUser"])
        assertEquals(true, body["clearAllProperties"])
        // Clear flags only present when true.
        assertFalse(body.containsKey("externalUserId"))
        assertFalse(body.containsKey("properties"))
        assertFalse(body.containsKey("clearPropertyKeys"))
    }

    @Test
    fun userOmitsClearFlagsWhenFalse() {
        val user = AttriaxRequestBuilders.buildUser(
            projectToken = "t",
            externalUserId = "u",
            externalUserName = "Ada",
            properties = mapOf("plan" to "pro"),
            deviceId = "d",
            deviceIdSource = "android_ssaid",
        )
        val body = user.body
        assertEquals("u", body["externalUserId"])
        assertEquals("Ada", body["externalUserName"])
        assertEquals(mapOf("plan" to "pro"), body["properties"])
        assertFalse(body.containsKey("clearExternalUser"))
        assertFalse(body.containsKey("clearAllProperties"))
        assertFalse(body.containsKey("clearPropertyKeys"))
    }

    // -------- uninstall token (SdkRegisterUninstallTokenDto) --------

    @Test
    fun uninstallTokenMatchesDto() {
        val req = AttriaxRequestBuilders.buildUninstallToken(
            projectToken = "t",
            deviceId = "d",
            deviceIdSource = "android_ssaid",
            platform = "android",
            provider = "fcm",
            token = "fcm-token",
            metadata = null,
        )
        val body = req.body
        assertEquals(AttriaxEndpoints.UNINSTALL_TOKENS, req.path)
        assertEquals("d", body["deviceId"])
        assertEquals("android", body["platform"])
        assertEquals("fcm", body["provider"])
        assertEquals("fcm-token", body["token"])
        assertFalse(body.containsKey("metadata"))
    }

    @Test
    fun uninstallTokenOmitsNullToken() {
        val req = AttriaxRequestBuilders.buildUninstallToken(
            projectToken = "t",
            deviceId = "d",
            deviceIdSource = "android_ssaid",
            platform = "android",
            provider = "fcm",
            token = null,
            metadata = null,
        )
        assertFalse(req.body.containsKey("token"))
    }

    // -------- receipt validate (SdkV1RevenueReceiptValidateDto — direct) --------

    @Test
    fun receiptValidateMatchesDto() {
        val body = AttriaxRequestBuilders.buildReceiptValidate(
            projectToken = "t",
            receipt = "R",
            deviceId = "d",
            clientOccurredAtIso = "2026-01-01T00:00:00.000Z",
            provider = "play",
            environment = "production",
            transactionId = "tx",
            productId = "p",
            test = true,
        )
        assertEquals("t", body["projectToken"])
        assertEquals("R", body["receipt"])
        assertEquals("d", body["deviceId"])
        assertEquals("2026-01-01T00:00:00.000Z", body["clientOccurredAt"])
        assertEquals("play", body["provider"])
        assertEquals("production", body["environment"])
        assertEquals("tx", body["transactionId"])
        assertEquals("p", body["productId"])
        assertEquals(true, body["test"])
    }

    @Test
    fun receiptValidateOmitsNullOptionals() {
        val body = AttriaxRequestBuilders.buildReceiptValidate(
            projectToken = "t",
            receipt = "R",
            deviceId = null,
            clientOccurredAtIso = "2026-01-01T00:00:00.000Z",
            provider = null,
            environment = null,
            transactionId = null,
            productId = null,
            test = null,
        )
        assertTrue(body.containsKey("receipt"))
        assertFalse(body.containsKey("deviceId"))
        assertFalse(body.containsKey("provider"))
        assertFalse(body.containsKey("test"))
    }
}
