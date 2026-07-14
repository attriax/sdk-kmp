package com.attriax.sdk.internal.request

import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.consent.AttriaxConsentStateWire
import com.attriax.sdk.internal.consent.AttriaxGdprConsentState
import com.attriax.sdk.internal.consent.AttriaxGdprConsentValues
import com.attriax.sdk.internal.consent.AttriaxHttpConsentTransport
import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.request.AttriaxBatching.QueuedItem
import com.attriax.sdk.internal.skan.AttriaxSkanCvConfigDecoder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden wire-shape fixtures — the canonical, centrally-pinned encoding of every
 * core request body (and a few response/enum shapes). Each fixture asserts the EXACT
 * JSON the current serializers produce, including field ORDER, null-OMISSION, enum
 * CASING, and integral-double formatting.
 *
 * This is the reference the Unity / Flutter / JS wrappers mirror. Any drift in a core
 * builder — a reordered field, a renamed key, a newly-emitted-or-omitted optional —
 * changes a byte here and fails the corresponding fixture. The C-ABI dispatch KEYS
 * are pinned separately by `AttriaxDispatchContract` + its guards.
 */
class AttriaxWireContractGoldenTest {

    private val iso = "2026-07-13T00:00:00.000Z"

    /** Full app/device/sdk context used by the open/session/crash fixtures. */
    private val context = AttriaxContextSnapshot(
        packageName = "com.attriax.demo",
        appVersion = "1.0.0",
        appBuildNumber = "10",
        deviceModel = "Pixel 8",
        deviceManufacturer = "Google",
        osVersion = "14",
        deviceTimezone = "UTC",
        deviceLocale = "en-US",
    )

    // ---------------- app-open ----------------

    @Test
    fun openBodyIsPinned() {
        val open = AttriaxRequestBuilders.buildOpen(
            projectToken = "pt",
            context = context,
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
            isFirstLaunch = true,
            sessionId = null,
            sessionStartedAtIso = null,
        )
        assertEquals(
            """{"projectToken":"pt","platform":"android","deviceId":"dev-1",""" +
                """"deviceIdSource":"android_ssaid","isFirstLaunch":true,""" +
                """"sdk":{"apiVersion":"v1","packageVersion":"0.6.0"},""" +
                """"app":{"version":"1.0.0","buildNumber":"10","packageName":"com.attriax.demo"},""" +
                """"device":{"model":"Pixel 8","manufacturer":"Google","osVersion":"14",""" +
                """"timezone":"UTC","language":"en-US"}}""",
            Json.encode(open.body),
        )
        assertEquals(AttriaxEndpoints.OPEN, open.path)
    }

    @Test
    fun openBodyWithCcpaAndAttStatusIsPinned() {
        val open = AttriaxRequestBuilders.buildOpen(
            projectToken = "pt",
            context = context,
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
            isFirstLaunch = true,
            sessionId = null,
            sessionStartedAtIso = null,
            attStatus = "authorized",
            doNotSell = true,
            usPrivacy = "1YYN",
        )
        assertEquals(
            """{"projectToken":"pt","platform":"android","deviceId":"dev-1",""" +
                """"deviceIdSource":"android_ssaid","isFirstLaunch":true,""" +
                """"sdk":{"apiVersion":"v1","packageVersion":"0.6.0"},""" +
                """"app":{"version":"1.0.0","buildNumber":"10","packageName":"com.attriax.demo"},""" +
                """"device":{"model":"Pixel 8","manufacturer":"Google","osVersion":"14",""" +
                """"timezone":"UTC","language":"en-US"},""" +
                """"attStatus":"authorized","doNotSell":true,"usPrivacy":"1YYN"}""",
            Json.encode(open.body),
        )
    }

    // ---------------- event / purchase ----------------

    @Test
    fun eventBodyIsPinned() {
        val event = AttriaxRequestBuilders.buildEvent(
            projectToken = "pt",
            eventName = "level_up",
            eventData = linkedMapOf<String, Any?>("level" to 5),
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
            sessionId = "sess-1",
            sessionRelativeTimeMs = 1500L,
            clientOccurredAtIso = iso,
        )
        assertEquals(
            """{"projectToken":"pt","eventName":"level_up","eventData":{"level":5},""" +
                """"deviceId":"dev-1","deviceIdSource":"android_ssaid","sessionId":"sess-1",""" +
                """"sessionRelativeTimeMs":1500,"clientOccurredAt":"2026-07-13T00:00:00.000Z"}""",
            Json.encode(event.body),
        )
    }

    @Test
    fun purchaseEventBodyIsPinned() {
        // The purchase helper lowers to a `purchase` event carrying revenue params;
        // this pins the resulting /events wire body (a non-integral double stays 9.99).
        val purchase = AttriaxRequestBuilders.buildEvent(
            projectToken = "pt",
            eventName = "purchase",
            eventData = linkedMapOf<String, Any?>(
                "revenue" to 9.99,
                "currency" to "USD",
                "productId" to "prod_1",
            ),
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
            sessionId = null,
            sessionRelativeTimeMs = null,
            clientOccurredAtIso = iso,
        )
        assertEquals(
            """{"projectToken":"pt","eventName":"purchase",""" +
                """"eventData":{"revenue":9.99,"currency":"USD","productId":"prod_1"},""" +
                """"deviceId":"dev-1","deviceIdSource":"android_ssaid",""" +
                """"clientOccurredAt":"2026-07-13T00:00:00.000Z"}""",
            Json.encode(purchase.body),
        )
    }

    // ---------------- user / identify (single + batch) ----------------

    @Test
    fun userBodyIsPinned() {
        val user = AttriaxRequestBuilders.buildUser(
            projectToken = "pt",
            externalUserId = "user-1",
            externalUserName = "Ada",
            properties = linkedMapOf<String, Any?>("plan" to "pro"),
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
            doNotSell = false,
            usPrivacy = "1YNN",
        )
        assertEquals(
            """{"projectToken":"pt","deviceId":"dev-1","deviceIdSource":"android_ssaid",""" +
                """"externalUserId":"user-1","externalUserName":"Ada",""" +
                """"properties":{"plan":"pro"},"doNotSell":false,"usPrivacy":"1YNN"}""",
            Json.encode(user.body),
        )
    }

    @Test
    fun eventBatchEnvelopeIsPinned() {
        fun event(name: String) = AttriaxRequestBuilders.buildEvent(
            projectToken = "pt",
            eventName = name,
            eventData = null,
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
            sessionId = null,
            sessionRelativeTimeMs = null,
            clientOccurredAtIso = iso,
        )
        val body = AttriaxBatching.buildBatchBody(
            listOf(QueuedItem("q1", event("a")), QueuedItem("q2", event("b"))),
        )
        assertEquals(
            """{"requestId":"batch_q1","projectToken":"pt","deviceId":"dev-1",""" +
                """"deviceIdSource":"android_ssaid","items":[""" +
                """{"kind":"event","body":{"eventName":"a","clientOccurredAt":"2026-07-13T00:00:00.000Z"}},""" +
                """{"kind":"event","body":{"eventName":"b","clientOccurredAt":"2026-07-13T00:00:00.000Z"}}]}""",
            Json.encode(body),
        )
    }

    @Test
    fun userBatchEnvelopeIsPinned() {
        val user = AttriaxRequestBuilders.buildUser(
            projectToken = "pt",
            externalUserId = "user-1",
            externalUserName = null,
            properties = null,
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
        )
        val body = AttriaxBatching.buildBatchBody(listOf(QueuedItem("q1", user)))
        assertEquals(
            """{"requestId":"batch_q1","projectToken":"pt","deviceId":"dev-1",""" +
                """"deviceIdSource":"android_ssaid","items":[""" +
                """{"kind":"user","body":{"externalUserId":"user-1"}}]}""",
            Json.encode(body),
        )
    }

    // ---------------- session / crash / notification ----------------

    @Test
    fun sessionBodyIsPinned() {
        val session = AttriaxRequestBuilders.buildSession(
            projectToken = "pt",
            kind = "start",
            sessionId = "sess-1",
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
            clientOccurredAtIso = iso,
            sessionRelativeTimeMs = 0L,
            platform = "android",
            locale = "en-US",
            isFirstLaunch = true,
            appVersion = "1.0.0",
            appBuildNumber = "10",
            appPackageName = "com.attriax.demo",
            sdkApiVersion = "v1",
            sdkPackageVersion = "0.6.0",
        )
        assertEquals(
            """{"projectToken":"pt","kind":"start","sessionId":"sess-1","deviceId":"dev-1",""" +
                """"deviceIdSource":"android_ssaid","sessionRelativeTimeMs":0,""" +
                """"clientOccurredAt":"2026-07-13T00:00:00.000Z","platform":"android","locale":"en-US",""" +
                """"isFirstLaunch":true,"appVersion":"1.0.0","appBuildNumber":"10",""" +
                """"appPackageName":"com.attriax.demo","sdkApiVersion":"v1","sdkPackageVersion":"0.6.0"}""",
            Json.encode(session.body),
        )
    }

    @Test
    fun crashBodyIsPinned() {
        val crash = AttriaxRequestBuilders.buildCrash(
            projectToken = "pt",
            context = context,
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
            source = "manual",
            isFatal = false,
            exceptionType = "RuntimeException",
            message = "boom",
            stackTrace = "line1",
            isFirstLaunch = false,
            clientOccurredAtIso = iso,
            reason = null,
            sessionId = null,
            sessionRelativeTimeMs = null,
            metadata = null,
        )
        assertEquals(
            """{"projectToken":"pt","deviceId":"dev-1","deviceIdSource":"android_ssaid",""" +
                """"source":"manual","clientOccurredAt":"2026-07-13T00:00:00.000Z","platform":"android",""" +
                """"isFatal":false,"exceptionType":"RuntimeException","message":"boom","stackTrace":"line1",""" +
                """"isFirstLaunch":false,"locale":"en-US","appVersion":"1.0.0","appBuildNumber":"10",""" +
                """"appPackageName":"com.attriax.demo","sdkApiVersion":"v1","sdkPackageVersion":"0.6.0"}""",
            Json.encode(crash.body),
        )
    }

    @Test
    fun notificationBodyIsPinned() {
        val notification = AttriaxRequestBuilders.buildNotification(
            projectToken = "pt",
            platform = "android",
            type = "opened",
            notificationId = "n-1",
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
            linkId = null,
            campaignId = null,
            title = null,
            source = "fcm",
            sessionId = null,
            occurredAtIso = iso,
            metadata = null,
        )
        assertEquals(
            """{"projectToken":"pt","deviceId":"dev-1","deviceIdSource":"android_ssaid",""" +
                """"type":"opened","notificationId":"n-1","source":"fcm","platform":"android",""" +
                """"occurredAt":"2026-07-13T00:00:00.000Z"}""",
            Json.encode(notification.body),
        )
    }

    // ---------------- deep-link resolve ----------------

    @Test
    fun resolveDeepLinkBodyIsPinned() {
        val resolve = AttriaxRequestBuilders.buildResolveDeepLink(
            projectToken = "pt",
            platform = "android",
            source = "attriax_sdk",
            isFirstLaunch = false,
            deviceId = "dev-1",
            deviceIdSource = "android_ssaid",
            rawUrl = "https://x.attriax.link/abc",
            linkPath = "/abc",
            sessionId = null,
            sessionRelativeTimeMs = null,
            metadata = null,
        )
        assertEquals(
            """{"projectToken":"pt","deviceId":"dev-1","deviceIdSource":"android_ssaid",""" +
                """"platform":"android","rawUrl":"https://x.attriax.link/abc","linkPath":"/abc",""" +
                """"source":"attriax_sdk","isFirstLaunch":false}""",
            Json.encode(resolve.body),
        )
    }

    // ---------------- receipt validate / ASA token ----------------

    @Test
    fun receiptValidateBodyIsPinned() {
        val body = AttriaxRequestBuilders.buildReceiptValidate(
            projectToken = "pt",
            receipt = "rcpt",
            deviceId = "dev-1",
            clientOccurredAtIso = iso,
            provider = "app_store",
            environment = "production",
            transactionId = "txn-1",
            productId = "prod_1",
            test = false,
        )
        assertEquals(
            """{"projectToken":"pt","deviceId":"dev-1","clientOccurredAt":"2026-07-13T00:00:00.000Z",""" +
                """"receipt":"rcpt","provider":"app_store","environment":"production",""" +
                """"transactionId":"txn-1","productId":"prod_1","test":false}""",
            Json.encode(body),
        )
    }

    @Test
    fun asaTokenBodyIsPinned() {
        val body = AttriaxRequestBuilders.buildAsaTokenBody(projectToken = "pt", token = "tok-1")
        assertEquals("""{"projectToken":"pt","token":"tok-1"}""", Json.encode(body))
    }

    // ---------------- GDPR consent (upsert body + state casing) ----------------

    @Test
    fun gdprConsentUpsertBodyIsPinned() {
        val captured = CapturingHttpClient()
        val transport = AttriaxHttpConsentTransport(captured)
        transport.upsertGdprConsent(
            projectToken = "pt",
            consentId = "consent-1",
            state = AttriaxGdprConsentState.GRANTED,
            values = AttriaxGdprConsentValues(analytics = true, attribution = false, adEvents = true),
            countryCode = "DE",
            regionSource = "ip",
            clientOccurredAtIso = iso,
        )
        assertEquals(AttriaxEndpoints.CONSENT_UPSERT, captured.lastPath)
        assertEquals(
            """{"projectToken":"pt","consentId":"consent-1","state":"granted",""" +
                """"values":{"analytics":true,"attribution":false,"adEvents":true},""" +
                """"countryCode":"DE","regionSource":"ip","clientOccurredAt":"2026-07-13T00:00:00.000Z"}""",
            captured.lastBody,
        )
    }

    @Test
    fun gdprConsentStateCasingIsPinned() {
        // snake_case wire tokens — critically `not_required`, not `notRequired`.
        assertEquals("unknown", AttriaxConsentStateWire.toWire(AttriaxGdprConsentState.UNKNOWN))
        assertEquals("not_required", AttriaxConsentStateWire.toWire(AttriaxGdprConsentState.NOT_REQUIRED))
        assertEquals("pending", AttriaxConsentStateWire.toWire(AttriaxGdprConsentState.PENDING))
        assertEquals("granted", AttriaxConsentStateWire.toWire(AttriaxGdprConsentState.GRANTED))
    }

    // ---------------- SKAN conversion-value config (response field contract) ----------------

    @Test
    fun skanCvConfigDecodeContractIsPinned() {
        // The cv-config surface is a GET whose response the decoder reads by these exact
        // field names; pin the field contract by decoding a representative payload.
        val decoded = Json.decode(
            """{"schemaVersion":3,"schemaUpdatedAt":"2026-07-13T00:00:00.000Z","enabled":true,""" +
                """"disclaimer":"d","rules":[{"id":"r1","groupId":"g1","groupDisplayName":"G1",""" +
                """"startBit":0,"bitCount":2,"rank":1,"bitContribution":1,"whenEvent":"purchase",""" +
                """"whenConditions":[{"paramKey":"tier","operator":"eq","value":"gold"}],""" +
                """"whenRevenue":{"operator":"gte","value":9.99},"coarseValue":"high","lockWindow":true}]}""",
        )
        val config = AttriaxSkanCvConfigDecoder.decode(decoded)
        assertEquals(3, config?.schemaVersion)
        assertEquals("2026-07-13T00:00:00.000Z", config?.schemaUpdatedAt)
        assertEquals(true, config?.enabled)
        assertEquals("d", config?.disclaimer)
        assertEquals(1, config?.rules?.size)
        val rule = config?.rules?.first()
        assertEquals("r1", rule?.id)
        assertEquals("purchase", rule?.whenEvent)
        assertEquals(2, rule?.bitCount)
        assertEquals(true, rule?.lockWindow)
        assertEquals(1, rule?.whenConditions?.size)
        assertEquals("tier", rule?.whenConditions?.first()?.paramKey)
    }

    /** Captures the last POST so the consent upsert body can be pinned byte-for-byte. */
    private class CapturingHttpClient : HttpClient {
        var lastPath: String? = null
        var lastBody: String? = null

        override fun post(path: String, body: String): HttpResponse {
            lastPath = path
            lastBody = body
            return HttpResponse(statusCode = 200, body = null)
        }
    }
}
