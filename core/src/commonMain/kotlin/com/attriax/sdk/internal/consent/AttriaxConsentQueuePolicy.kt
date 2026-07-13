package com.attriax.sdk.internal.consent

import com.attriax.sdk.AttriaxAdEventType
import com.attriax.sdk.AttriaxAnalyticsEventKeys
import com.attriax.sdk.internal.request.AttriaxApiRequest

/**
 * Pure queue-rewrite predicate policy for consent resolution.
 *
 * Mirrors the Flutter reference `AttriaxConsentQueuePolicy`
 * (`internal/consent/attriax_consent_queue_policy.dart`). Answers the three
 * questions the runtime's consent-resolution passes ask about each persisted
 * request. The engine models requests as a `kind` + body map (not a class per
 * endpoint), so these predicates key off [AttriaxApiRequest.kind] and inspect the
 * body for the ad-event name / device identity.
 *
 * The policy delegates the actual consent reasoning to the (also pure)
 * [AttriaxConsentPolicy] via the injected suppliers, so it holds no state and is
 * fully unit-testable.
 */
class AttriaxConsentQueuePolicy(
    private val isWaitingForGdprConsent: () -> Boolean,
    private val anonymousTrackingEnabled: () -> Boolean,
    private val allowsAttributionTracking: () -> Boolean,
    private val trackingDecisionFor: (AttriaxTrackingSignal) -> AttriaxTrackingDecision,
) {
    /**
     * The tracking decision for a queued request, or null for kinds that carry no
     * consent-gated signal (dynamic links, uninstall tokens handled separately).
     */
    fun trackingDecisionForQueuedRequest(request: AttriaxApiRequest): AttriaxTrackingDecision? =
        when (request.kind) {
            AttriaxApiRequest.KIND_TRACK_EVENT -> trackingDecisionFor(
                if (isAdEventName(eventNameOf(request))) AttriaxTrackingSignal.AD_EVENTS
                else AttriaxTrackingSignal.ANALYTICS,
            )
            AttriaxApiRequest.KIND_TRACK_CRASH -> trackingDecisionFor(AttriaxTrackingSignal.ANALYTICS)
            AttriaxApiRequest.KIND_TRACK_NOTIFICATION ->
                trackingDecisionFor(AttriaxTrackingSignal.ANALYTICS)
            AttriaxApiRequest.KIND_TRACK_SESSION -> trackingDecisionFor(AttriaxTrackingSignal.SESSION)
            AttriaxApiRequest.KIND_RESOLVE_DEEP_LINK ->
                trackingDecisionFor(AttriaxTrackingSignal.DEEP_LINK)
            else -> null
        }

    /**
     * PASS 1 predicate: after consent resolved to IDENTIFIED tracking, this
     * anonymous request may now have the device identity re-attached.
     */
    fun shouldIdentifyQueuedRequestForResolvedConsent(request: AttriaxApiRequest): Boolean {
        if (isWaitingForGdprConsent()) return false
        val decision = trackingDecisionForQueuedRequest(request) ?: return false
        return decision.capture && decision.attachDeviceIdentity
    }

    /**
     * PASS 3 predicate (negated by the caller): whether this request is still
     * allowed under the resolved consent. Anything not allowed is discarded with
     * reason `gdpr_consent_denied`.
     */
    fun isRequestAllowedByResolvedConsent(request: AttriaxApiRequest): Boolean =
        when (request.kind) {
            AttriaxApiRequest.KIND_TRACK_EVENT,
            AttriaxApiRequest.KIND_TRACK_CRASH,
            AttriaxApiRequest.KIND_TRACK_NOTIFICATION,
            AttriaxApiRequest.KIND_TRACK_SESSION,
            AttriaxApiRequest.KIND_RESOLVE_DEEP_LINK ->
                trackingDecisionForQueuedRequest(request)?.capture ?: false
            AttriaxApiRequest.KIND_USER -> allowsAttributionTracking()
            AttriaxApiRequest.KIND_OPEN -> allowsAttributionTracking()
            AttriaxApiRequest.KIND_REGISTER_UNINSTALL_TOKEN -> allowsAttributionTracking()
            AttriaxApiRequest.KIND_CREATE_DYNAMIC_LINK -> true
            else -> true
        }

    /**
     * PASS 2 predicate: after consent resolved, this request keeps being captured
     * but only ANONYMOUSLY (a declined-but-anonymous-capable category), so its
     * device identity must be stripped.
     */
    fun shouldAnonymizeQueuedRequest(request: AttriaxApiRequest): Boolean {
        if (isWaitingForGdprConsent() || !anonymousTrackingEnabled()) return false
        val decision = trackingDecisionForQueuedRequest(request) ?: return false
        return decision.capture && !decision.attachDeviceIdentity
    }

    private fun eventNameOf(request: AttriaxApiRequest): String =
        (request.body["eventName"] as? String).orEmpty()

    private fun isAdEventName(eventName: String): Boolean =
        eventName == AttriaxAnalyticsEventKeys.AD_REVENUE ||
            AttriaxAdEventType.values().any { it.eventName == eventName }
}
