package com.attriax.sdk.internal.consent

/**
 * Pure consent decision policy. Framework-free and fully
 * unit-testable. Mirrors the Flutter reference `AttriaxConsentPolicy`
 * (`internal/consent/attriax_consent_policy.dart`).
 *
 * TWO predicate families intentionally answer DIFFERENT questions over the same
 * consent state — do not treat them as synonyms:
 *
 *  * [allowsCategory] (STRICT identity gate): may we track this category with the
 *    device IDENTITY (and full runtime persistence)? Anonymous tracking does NOT
 *    relax a category the user explicitly declined under granted consent.
 *  * [canCaptureSignal] / [trackingDecisionFor] (PERMISSIVE anonymous-capture
 *    gate): may we CAPTURE this signal at all, possibly anonymously? With
 *    anonymous tracking enabled, a declined-but-anonymous-capable category is
 *    still captured (anonymized).
 */
class AttriaxConsentPolicy(
    private val gdprEnabled: Boolean,
    private val state: AttriaxGdprConsentState,
    private val values: AttriaxGdprConsentValues?,
    private val anonymousTrackingEnabled: Boolean,
) {
    val isWaitingForGdprConsent: Boolean
        get() = state == AttriaxGdprConsentState.PENDING ||
            state == AttriaxGdprConsentState.UNKNOWN

    /**
     * When GDPR is on, we are still waiting, and anonymous tracking is OFF, network
     * dispatch must be deferred (traffic buffers locally until consent resolves).
     */
    val shouldDeferNetworkDispatch: Boolean
        get() = gdprEnabled && isWaitingForGdprConsent && !anonymousTrackingEnabled

    /**
     * Strict identity gate: may [selector]'s category be tracked with the device
     * identity? Anonymous tracking does NOT relax a declined category.
     */
    fun allowsCategory(selector: (AttriaxGdprConsentValues) -> Boolean): Boolean {
        if (!gdprEnabled) return true
        return when (state) {
            AttriaxGdprConsentState.NOT_REQUIRED -> true
            AttriaxGdprConsentState.GRANTED -> values != null && selector(values)
            AttriaxGdprConsentState.PENDING,
            AttriaxGdprConsentState.UNKNOWN -> false
        }
    }

    fun canCaptureCategory(
        selector: (AttriaxGdprConsentValues) -> Boolean,
        allowWhileWaiting: Boolean,
    ): Boolean {
        if (!gdprEnabled) return true
        return when (state) {
            AttriaxGdprConsentState.NOT_REQUIRED -> true
            AttriaxGdprConsentState.GRANTED -> values != null && selector(values)
            AttriaxGdprConsentState.PENDING,
            AttriaxGdprConsentState.UNKNOWN -> allowWhileWaiting
        }
    }

    /**
     * Permissive capture gate: may this signal be captured at all, possibly
     * anonymously? With [anonymousTrackingEnabled] on, a declined but
     * anonymous-capable signal is still captured (anonymized) — intentional.
     */
    fun canCaptureSignal(signal: AttriaxTrackingSignal): Boolean {
        if (!gdprEnabled) return true
        return when (state) {
            AttriaxGdprConsentState.NOT_REQUIRED -> true
            AttriaxGdprConsentState.GRANTED ->
                values != null &&
                    (isSignalGranted(signal, values) ||
                        (anonymousTrackingEnabled && isAnonymousCapableSignal(signal)))
            AttriaxGdprConsentState.PENDING,
            AttriaxGdprConsentState.UNKNOWN -> canCaptureWhileWaiting(signal)
        }
    }

    fun trackingDecisionFor(signal: AttriaxTrackingSignal): AttriaxTrackingDecision {
        if (!gdprEnabled) return AttriaxTrackingDecision.IDENTIFIED

        if (state == AttriaxGdprConsentState.UNKNOWN ||
            state == AttriaxGdprConsentState.PENDING
        ) {
            if (!canCaptureWhileWaiting(signal)) return AttriaxTrackingDecision.WITHHELD
            return AttriaxTrackingDecision(
                capture = true,
                identityMode = AttriaxTrackingIdentityMode.ANONYMOUS,
                deferNetwork = !anonymousTrackingEnabled,
            )
        }

        if (state == AttriaxGdprConsentState.NOT_REQUIRED) {
            return AttriaxTrackingDecision.IDENTIFIED
        }

        val currentValues = values
        if (state != AttriaxGdprConsentState.GRANTED || currentValues == null) {
            return AttriaxTrackingDecision.WITHHELD
        }

        if (isSignalGranted(signal, currentValues)) {
            return AttriaxTrackingDecision.IDENTIFIED
        }

        if (anonymousTrackingEnabled && isAnonymousCapableSignal(signal)) {
            return AttriaxTrackingDecision.ANONYMOUS
        }

        return AttriaxTrackingDecision.WITHHELD
    }

    /**
     * Which signals may be captured (anonymously) while consent is still
     * pending/unknown. Analytics, ad-events, session, and deep-link
     * diagnostics are anonymous-capable; attribution and uninstall tracking are
     * identity-linked and NEVER captured while waiting.
     */
    fun canCaptureWhileWaiting(signal: AttriaxTrackingSignal): Boolean = when (signal) {
        AttriaxTrackingSignal.ANALYTICS,
        AttriaxTrackingSignal.AD_EVENTS,
        AttriaxTrackingSignal.SESSION,
        AttriaxTrackingSignal.DEEP_LINK -> true
        AttriaxTrackingSignal.ATTRIBUTION,
        AttriaxTrackingSignal.UNINSTALL_TRACKING -> false
    }

    fun isAnonymousCapableSignal(signal: AttriaxTrackingSignal): Boolean =
        canCaptureWhileWaiting(signal)

    fun isSignalGranted(
        signal: AttriaxTrackingSignal,
        values: AttriaxGdprConsentValues,
    ): Boolean = when (signal) {
        AttriaxTrackingSignal.ANALYTICS -> values.analytics
        AttriaxTrackingSignal.AD_EVENTS -> values.adEvents
        AttriaxTrackingSignal.ATTRIBUTION -> values.attribution
        AttriaxTrackingSignal.SESSION -> values.analytics || values.adEvents
        AttriaxTrackingSignal.DEEP_LINK -> values.attribution
        AttriaxTrackingSignal.UNINSTALL_TRACKING -> values.attribution
    }
}
