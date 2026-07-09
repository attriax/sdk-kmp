package com.attriax.sdk

import com.attriax.sdk.internal.consent.AttriaxGdprConsentState
import com.attriax.sdk.internal.consent.AttriaxGdprConsentValues

/**
 * Regulation-scoped consent surface exposed as `attriax.consent` (PARITY §5).
 * Hosts the GDPR helpers under [gdpr] and the Apple ATT helpers under [att]. SKAN is
 * a separate Apple surface exposed as `attriax.skan` (see [AttriaxSkan]), not here.
 */
class AttriaxConsent internal constructor(engine: Attriax) {
    /** GDPR-specific consent state and actions for the current device. */
    val gdpr: AttriaxGdprConsent = AttriaxGdprConsent(engine)

    /** Apple App Tracking Transparency (ATT) status + request helpers. */
    val att: AttriaxAttConsent = AttriaxAttConsent(engine)

    /** CCPA "do not sell / share" election + US-Privacy string helpers. */
    val ccpa: AttriaxCcpaConsent = AttriaxCcpaConsent(engine)
}

/**
 * CCPA "do not sell / share" actions (Epic 10.1, PARITY §5 — the `consent.ccpa`
 * sub-surface, mirroring [AttriaxAttConsent]).
 *
 * The election is LATCHING server-side; the SDK only reports the current value.
 * [doNotSell]/[usPrivacy] are seeded from [AttriaxConfig.doNotSell] /
 * [AttriaxConfig.usPrivacy] and overridable at runtime via [setDoNotSell] /
 * [setUsPrivacy] / [set]; a runtime change is reflected on the NEXT app-open /
 * identify. `null` doNotSell and `null`/blank usPrivacy are OMITTED from the wire
 * entirely (a no-CCPA app is byte-identical to today); an explicit `false`
 * doNotSell IS emitted (it may clear a prior server-side latch).
 */
class AttriaxCcpaConsent internal constructor(private val engine: Attriax) {

    /**
     * Current CCPA do-not-sell election: the wrapper-supplied value if one was set
     * (via [AttriaxConfig.doNotSell] or [setDoNotSell]/[set]), else `null` (unset →
     * omitted from the wire).
     */
    val doNotSell: Boolean? get() = engine.ccpaDoNotSell

    /**
     * Current raw IAB US-Privacy string: the wrapper-supplied value if one was set
     * (via [AttriaxConfig.usPrivacy] or [setUsPrivacy]/[set]), else `null`
     * (unset/blank → omitted from the wire).
     */
    val usPrivacy: String? get() = engine.ccpaUsPrivacy

    /**
     * SET the CCPA do-not-sell election. The engine reports it via [doNotSell] and
     * emits it (unless `null`) TOP-LEVEL on the next app-open / identify. An explicit
     * `false` is sent (it may clear a prior server-side latch); `null` returns to the
     * omitted (unset) state.
     */
    fun setDoNotSell(doNotSell: Boolean?) = engine.setCcpaDoNotSell(doNotSell)

    /**
     * SET the raw IAB US-Privacy string (e.g. `1YYN`). The engine reports it via
     * [usPrivacy] and emits it (unless `null`/blank) TOP-LEVEL on the next app-open /
     * identify, capped at 16 chars. `null`/blank returns to the omitted state.
     */
    fun setUsPrivacy(usPrivacy: String?) = engine.setCcpaUsPrivacy(usPrivacy)

    /** Convenience combined setter for both CCPA fields (see [setDoNotSell]/[setUsPrivacy]). */
    fun set(doNotSell: Boolean?, usPrivacy: String?) {
        engine.setCcpaDoNotSell(doNotSell)
        engine.setCcpaUsPrivacy(usPrivacy)
    }
}

/**
 * Apple App Tracking Transparency actions (PARITY §5 — Flutter reference
 * `AttriaxAttConsent`, `attriax_consent.dart:69-80`).
 *
 * ATT is an Apple-only framework, so on every currently-built target
 * (android/jvm/native) [status] reports the wrapper-supplied value if one was set
 * (via [AttriaxConfig.attStatus] or [setStatus]), otherwise
 * [AttriaxAttStatus.UNKNOWN] from the platform seam; [requestAuthorization] is a
 * no-op returning UNKNOWN. The future iosMain actual (deferred to Mac) wires both
 * to `ATTrackingManager`.
 */
class AttriaxAttConsent internal constructor(private val engine: Attriax) {

    /**
     * Current ATT status: the wrapper-supplied value if one was set, otherwise the
     * platform seam (UNKNOWN on every currently-built target). Mirrors Flutter's
     * `getTrackingAuthorizationStatus()`.
     */
    val status: AttriaxAttStatus get() = engine.attStatus

    /**
     * Wrapper-supply entrypoint: SET the ATT status obtained natively by a host
     * wrapper (Flutter / Unity / React Native iOS plugin). The engine then reports
     * it via [status] and emits it (unless UNKNOWN) on the next app-open. This is
     * the off-iOS bridge that lets a native ATT prompt drive the core.
     */
    fun setStatus(status: AttriaxAttStatus) = engine.setAttStatus(status)

    /**
     * Request ATT authorization (Apple prompt). Mirrors Flutter's
     * `requestTrackingAuthorization(timeout:)`. Blocking with an optional
     * [timeoutMs] — call off the main thread. On every currently-built target this
     * is a no-op returning [AttriaxAttStatus.UNKNOWN]; the future iosMain actual
     * shows the real prompt. A resolved (non-UNKNOWN) result is latched as [status].
     */
    fun requestAuthorization(timeoutMs: Long? = null): AttriaxAttStatus =
        engine.requestAttAuthorization(timeoutMs)
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
