package com.attriax.sdk.internal.consent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure consent-policy coverage (PARITY §5, rows C1/C4). Exercises the two gate
 * families (strict [AttriaxConsentPolicy.allowsCategory] vs permissive
 * [AttriaxConsentPolicy.canCaptureSignal]) and the anonymous-capable signal set.
 */
class AttriaxConsentPolicyTest {

    private fun policy(
        gdprEnabled: Boolean = true,
        state: AttriaxGdprConsentState,
        values: AttriaxGdprConsentValues? = null,
        anonymous: Boolean = true,
    ) = AttriaxConsentPolicy(gdprEnabled, state, values, anonymous)

    // -------- row C1: states / waiting semantics --------

    @Test
    fun `unknown and pending are waiting states`() {
        assertTrue(policy(state = AttriaxGdprConsentState.UNKNOWN).isWaitingForGdprConsent)
        assertTrue(policy(state = AttriaxGdprConsentState.PENDING).isWaitingForGdprConsent)
        assertFalse(policy(state = AttriaxGdprConsentState.GRANTED).isWaitingForGdprConsent)
        assertFalse(policy(state = AttriaxGdprConsentState.NOT_REQUIRED).isWaitingForGdprConsent)
    }

    @Test
    fun `gdpr disabled short-circuits every gate to allowed`() {
        val p = policy(gdprEnabled = false, state = AttriaxGdprConsentState.UNKNOWN)
        assertTrue(p.allowsCategory { it.analytics })
        assertTrue(p.canCaptureSignal(AttriaxTrackingSignal.ATTRIBUTION))
        assertEquals(
            AttriaxTrackingIdentityMode.IDENTIFIED,
            p.trackingDecisionFor(AttriaxTrackingSignal.ATTRIBUTION).identityMode,
        )
    }

    // -------- row C4: category gate matrix (which signals are anon-capable) --------

    @Test
    fun `anonymous-capable signals are analytics adEvents session deepLink only`() {
        val p = policy(state = AttriaxGdprConsentState.PENDING)
        assertTrue(p.isAnonymousCapableSignal(AttriaxTrackingSignal.ANALYTICS))
        assertTrue(p.isAnonymousCapableSignal(AttriaxTrackingSignal.AD_EVENTS))
        assertTrue(p.isAnonymousCapableSignal(AttriaxTrackingSignal.SESSION))
        assertTrue(p.isAnonymousCapableSignal(AttriaxTrackingSignal.DEEP_LINK))
        assertFalse(p.isAnonymousCapableSignal(AttriaxTrackingSignal.ATTRIBUTION))
        assertFalse(p.isAnonymousCapableSignal(AttriaxTrackingSignal.UNINSTALL_TRACKING))
    }

    @Test
    fun `while waiting anon signals capture anonymously and attribution is withheld`() {
        val p = policy(state = AttriaxGdprConsentState.PENDING, anonymous = true)
        val analytics = p.trackingDecisionFor(AttriaxTrackingSignal.ANALYTICS)
        assertTrue(analytics.capture)
        assertEquals(AttriaxTrackingIdentityMode.ANONYMOUS, analytics.identityMode)
        assertFalse(analytics.deferNetwork)

        val attribution = p.trackingDecisionFor(AttriaxTrackingSignal.ATTRIBUTION)
        assertFalse(attribution.capture)
        assertEquals(AttriaxTrackingIdentityMode.WITHHELD, attribution.identityMode)
    }

    @Test
    fun `while waiting with anonymous OFF anon signals defer network`() {
        val p = policy(state = AttriaxGdprConsentState.PENDING, anonymous = false)
        val analytics = p.trackingDecisionFor(AttriaxTrackingSignal.ANALYTICS)
        assertTrue(analytics.capture)
        assertEquals(AttriaxTrackingIdentityMode.ANONYMOUS, analytics.identityMode)
        assertTrue(analytics.deferNetwork)
        assertTrue(p.shouldDeferNetworkDispatch)
    }

    // -------- strict vs permissive under GRANTED --------

    @Test
    fun `granted-but-declined analytics is withheld for identity yet captured anonymously`() {
        val values = AttriaxGdprConsentValues(analytics = false, attribution = false, adEvents = false)
        val p = policy(state = AttriaxGdprConsentState.GRANTED, values = values, anonymous = true)

        // Strict identity gate: declined analytics is NOT allowed with identity.
        assertFalse(p.allowsCategory { it.analytics })

        // Permissive capture gate: still captured (anonymously) because analytics
        // is an anonymous-capable signal and anonymous tracking is on.
        assertTrue(p.canCaptureSignal(AttriaxTrackingSignal.ANALYTICS))
        val decision = p.trackingDecisionFor(AttriaxTrackingSignal.ANALYTICS)
        assertTrue(decision.capture)
        assertEquals(AttriaxTrackingIdentityMode.ANONYMOUS, decision.identityMode)
    }

    @Test
    fun `granted-but-declined analytics with anonymous OFF is fully withheld`() {
        val values = AttriaxGdprConsentValues(analytics = false, attribution = false, adEvents = false)
        val p = policy(state = AttriaxGdprConsentState.GRANTED, values = values, anonymous = false)
        assertFalse(p.canCaptureSignal(AttriaxTrackingSignal.ANALYTICS))
        assertEquals(
            AttriaxTrackingIdentityMode.WITHHELD,
            p.trackingDecisionFor(AttriaxTrackingSignal.ANALYTICS).identityMode,
        )
    }

    @Test
    fun `granted analytics yields identified capture`() {
        val values = AttriaxGdprConsentValues(analytics = true, attribution = false, adEvents = false)
        val p = policy(state = AttriaxGdprConsentState.GRANTED, values = values)
        assertTrue(p.allowsCategory { it.analytics })
        val decision = p.trackingDecisionFor(AttriaxTrackingSignal.ANALYTICS)
        assertEquals(AttriaxTrackingIdentityMode.IDENTIFIED, decision.identityMode)
    }

    @Test
    fun `session is granted when either analytics or adEvents is granted`() {
        val onlyAd = AttriaxGdprConsentValues(analytics = false, attribution = false, adEvents = true)
        val p = policy(state = AttriaxGdprConsentState.GRANTED, values = onlyAd)
        assertTrue(p.isSignalGranted(AttriaxTrackingSignal.SESSION, onlyAd))
        assertEquals(
            AttriaxTrackingIdentityMode.IDENTIFIED,
            p.trackingDecisionFor(AttriaxTrackingSignal.SESSION).identityMode,
        )
    }

    @Test
    fun `notRequired allows all categories with identity`() {
        val p = policy(state = AttriaxGdprConsentState.NOT_REQUIRED)
        assertTrue(p.allowsCategory { it.attribution })
        assertEquals(
            AttriaxTrackingIdentityMode.IDENTIFIED,
            p.trackingDecisionFor(AttriaxTrackingSignal.ATTRIBUTION).identityMode,
        )
    }
}
