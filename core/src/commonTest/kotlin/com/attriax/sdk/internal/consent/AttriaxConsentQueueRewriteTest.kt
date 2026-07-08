package com.attriax.sdk.internal.consent

import com.attriax.sdk.internal.request.AttriaxApiRequest
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the three consent-resolution queue-rewrite passes (PARITY §5,
 * row C5): (1) identify anonymous requests, (2) anonymize denied-category
 * requests, (3) discard now-disallowed requests. Exercises the pure predicate
 * policy [AttriaxConsentQueuePolicy] + the body-map rewrites
 * [AttriaxConsentRequestRewrites].
 */
class AttriaxConsentQueueRewriteTest {

    private fun event(deviceId: String? = null, name: String = "level_up"): AttriaxApiRequest {
        val body = LinkedHashMap<String, Any?>()
        body["projectToken"] = "tok"
        body["eventName"] = name
        deviceId?.let {
            body["deviceId"] = it
            body["deviceIdSource"] = "android_ssaid"
        }
        body["clientOccurredAt"] = "2026-07-06T00:00:00.000Z"
        return AttriaxApiRequest(AttriaxApiRequest.KIND_TRACK_EVENT, AttriaxEndpoints.EVENTS, body)
    }

    private fun user(): AttriaxApiRequest {
        val body = linkedMapOf<String, Any?>(
            "projectToken" to "tok",
            "deviceId" to "dev-1",
            "deviceIdSource" to "android_ssaid",
            "externalUserId" to "u1",
        )
        return AttriaxApiRequest(AttriaxApiRequest.KIND_USER, AttriaxEndpoints.USERS, body)
    }

    private fun policyFor(
        state: AttriaxGdprConsentState,
        values: AttriaxGdprConsentValues?,
        anonymous: Boolean,
    ): AttriaxConsentQueuePolicy {
        val consentPolicy = AttriaxConsentPolicy(
            gdprEnabled = true,
            state = state,
            values = values,
            anonymousTrackingEnabled = anonymous,
        )
        return AttriaxConsentQueuePolicy(
            isWaitingForGdprConsent = { consentPolicy.isWaitingForGdprConsent },
            anonymousTrackingEnabled = { anonymous },
            allowsAttributionTracking = { consentPolicy.allowsCategory { it.attribution } },
            trackingDecisionFor = { consentPolicy.trackingDecisionFor(it) },
        )
    }

    // -------- PASS 1: identify --------

    @Test
    fun `pass1 identifies anonymous event when analytics granted`() {
        val values = AttriaxGdprConsentValues(analytics = true, attribution = false, adEvents = false)
        val policy = policyFor(AttriaxGdprConsentState.GRANTED, values, anonymous = true)
        val anon = event(deviceId = null)

        assertTrue(policy.shouldIdentifyQueuedRequestForResolvedConsent(anon))
        val rewritten = AttriaxConsentRequestRewrites.identify(anon, "dev-9", "android_ssaid")
        assertEquals("dev-9", rewritten?.body?.get("deviceId"))
        assertEquals("android_ssaid", rewritten?.body?.get("deviceIdSource"))
    }

    @Test
    fun `pass1 leaves an already-identified request unchanged`() {
        val identified = event(deviceId = "dev-1")
        assertNull(AttriaxConsentRequestRewrites.identify(identified, "dev-9", "android_ssaid"))
    }

    @Test
    fun `pass1 does not identify while still waiting`() {
        val values: AttriaxGdprConsentValues? = null
        val policy = policyFor(AttriaxGdprConsentState.PENDING, values, anonymous = true)
        assertFalse(policy.shouldIdentifyQueuedRequestForResolvedConsent(event(deviceId = null)))
    }

    // -------- PASS 2: anonymize --------

    @Test
    fun `pass2 anonymizes an identified event when analytics declined but anon on`() {
        val values = AttriaxGdprConsentValues(analytics = false, attribution = false, adEvents = false)
        val policy = policyFor(AttriaxGdprConsentState.GRANTED, values, anonymous = true)
        val identified = event(deviceId = "dev-1")

        assertTrue(policy.shouldAnonymizeQueuedRequest(identified))
        val stripped = AttriaxConsentRequestRewrites.anonymize(identified)
        assertNull(stripped.body["deviceId"])
        assertNull(stripped.body["deviceIdSource"])
        // Non-identity fields survive.
        assertEquals("level_up", stripped.body["eventName"])
    }

    @Test
    fun `pass2 does not anonymize a granted-identified request`() {
        val values = AttriaxGdprConsentValues(analytics = true, attribution = false, adEvents = false)
        val policy = policyFor(AttriaxGdprConsentState.GRANTED, values, anonymous = true)
        assertFalse(policy.shouldAnonymizeQueuedRequest(event(deviceId = "dev-1")))
    }

    // -------- PASS 3: discard --------

    @Test
    fun `pass3 discards a user request when attribution declined`() {
        val values = AttriaxGdprConsentValues(analytics = true, attribution = false, adEvents = false)
        val policy = policyFor(AttriaxGdprConsentState.GRANTED, values, anonymous = true)
        // user is attribution-gated → not allowed when attribution declined.
        assertFalse(policy.isRequestAllowedByResolvedConsent(user()))
        // an analytics event is still allowed (captured anonymously).
        assertTrue(policy.isRequestAllowedByResolvedConsent(event(deviceId = "dev-1")))
    }

    @Test
    fun `pass3 discards analytics event when analytics declined and anon OFF`() {
        val values = AttriaxGdprConsentValues(analytics = false, attribution = false, adEvents = false)
        val policy = policyFor(AttriaxGdprConsentState.GRANTED, values, anonymous = false)
        assertFalse(policy.isRequestAllowedByResolvedConsent(event(deviceId = "dev-1")))
    }

    @Test
    fun `ad-revenue event is classified as an adEvents signal`() {
        val values = AttriaxGdprConsentValues(analytics = false, attribution = false, adEvents = true)
        val policy = policyFor(AttriaxGdprConsentState.GRANTED, values, anonymous = false)
        // adEvents granted → ad_revenue event allowed with identity.
        assertTrue(policy.isRequestAllowedByResolvedConsent(event(deviceId = "dev-1", name = "ad_revenue")))
        // a plain analytics event with analytics declined + anon off → dropped.
        assertFalse(policy.isRequestAllowedByResolvedConsent(event(deviceId = "dev-1", name = "level_up")))
    }
}
