package com.attriax.sdk

import com.attriax.sdk.internal.consent.AttriaxGdprConsentState
import com.attriax.sdk.internal.consent.AttriaxGdprConsentValues

/**
 * Regulation-scoped consent surface exposed as `attriax.consent` (PARITY §5).
 * Currently hosts the GDPR helpers under [gdpr]; ATT/SKAN are iOS-only and are
 * not mirrored here (PARITY "Do NOT over-port").
 */
class AttriaxConsent internal constructor(engine: Attriax) {
    /** GDPR-specific consent state and actions for the current device. */
    val gdpr: AttriaxGdprConsent = AttriaxGdprConsent(engine)
}

/**
 * GDPR consent state and actions for the current device (PARITY §5, rows C1–C5).
 * Mirrors the Flutter reference `AttriaxGdprConsent` (`attriax_consent.dart:87`).
 *
 * Until consent is granted or marked not required, identified tracking is held
 * back per the configured anonymous-tracking policy. All decisions apply locally
 * IMMEDIATELY and sync to Attriax in the background (generation-guarded).
 */
class AttriaxGdprConsent internal constructor(private val engine: Attriax) {

    /** Current local GDPR consent state. */
    val state: AttriaxGdprConsentState get() = engine.gdprConsentState

    /** Last stored category values, or null before consent is granted. */
    val values: AttriaxGdprConsentValues? get() = engine.gdprConsentValues

    /** Whether the SDK is currently waiting for an explicit GDPR decision. */
    val isWaitingForConsent: Boolean get() = engine.isWaitingForGdprConsent

    /**
     * Resolve whether this device needs a GDPR consent decision. With
     * [localOnly] the SDK answers from stored state only; otherwise it may ask
     * Attriax for the current status. Performs blocking I/O when [localOnly] is
     * false — call off the main thread.
     */
    fun needsConsent(localOnly: Boolean = false): Boolean =
        engine.needsGdprConsent(localOnly = localOnly)

    /**
     * Store granted GDPR consent category values. Local behavior updates
     * immediately; the decision syncs to Attriax in the background.
     */
    fun setConsent(analytics: Boolean, attribution: Boolean, adEvents: Boolean) =
        engine.setGdprConsent(analytics = analytics, attribution = attribution, adEvents = adEvents)

    /** Mark GDPR consent as not required for this device. */
    fun setNotRequired() = engine.setGdprConsentNotRequired()

    /** Clear the local GDPR decision and return the SDK to pending evaluation. */
    fun reset() = engine.resetGdprConsent()

    /**
     * Request deletion of device-linked GDPR data on the Attriax backend. On
     * success this also clears local SDK state and returns the SDK to pre-init.
     * Performs blocking I/O — call off the main thread.
     */
    fun requestDataErasure() = engine.requestGdprDataErasure()
}
