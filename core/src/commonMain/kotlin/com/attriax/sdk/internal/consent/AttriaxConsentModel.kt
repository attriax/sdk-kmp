package com.attriax.sdk.internal.consent

/**
 * Local GDPR consent state for the current SDK device.
 *
 * Mirrors the Flutter reference `AttriaxGdprConsentState`
 * (`attriax_consent.dart:6-18`). Four states drive the whole capture/identity
 * policy: [UNKNOWN] (default), [NOT_REQUIRED], [PENDING], [GRANTED].
 */
enum class AttriaxGdprConsentState {
    /** Consent has not been checked or set yet. */
    UNKNOWN,

    /** GDPR consent is not required for this device. */
    NOT_REQUIRED,

    /** GDPR consent is required and the SDK is waiting for a decision. */
    PENDING,

    /** Consent values have been granted and stored. */
    GRANTED,
}

/**
 * Category-level GDPR consent values: three independent booleans.
 *
 * * [analytics] — analytics, session, crash, and diagnostic tracking.
 * * [attribution] — attribution, install referrer, deep-link attribution, identity.
 * * [adEvents] — ad-event measurement and related revenue analytics.
 */
data class AttriaxGdprConsentValues(
    val analytics: Boolean,
    val attribution: Boolean,
    val adEvents: Boolean,
)

/**
 * The distinct signal families the consent policy reasons about.
 * Mirrors the Flutter `AttriaxTrackingSignal` enum.
 */
enum class AttriaxTrackingSignal {
    ANALYTICS,
    AD_EVENTS,
    ATTRIBUTION,
    SESSION,
    DEEP_LINK,
    UNINSTALL_TRACKING,
}

/** How (if at all) device identity is attached to a captured signal. */
enum class AttriaxTrackingIdentityMode { IDENTIFIED, ANONYMOUS, WITHHELD }

/**
 * The resolved capture decision for a single signal under the current consent
 * state (mirrors Flutter `AttriaxTrackingDecision`). [capture] gates whether the
 * signal is enqueued at all; [identityMode] whether the device identity is
 * stamped; [deferNetwork] whether the request must buffer locally (anonymous
 * tracking disabled while waiting).
 */
data class AttriaxTrackingDecision(
    val capture: Boolean,
    val identityMode: AttriaxTrackingIdentityMode,
    val deferNetwork: Boolean,
) {
    /** True only for [AttriaxTrackingIdentityMode.IDENTIFIED]. */
    val attachDeviceIdentity: Boolean
        get() = identityMode == AttriaxTrackingIdentityMode.IDENTIFIED

    val sendNetworkDirectly: Boolean get() = capture && !deferNetwork

    companion object {
        val IDENTIFIED = AttriaxTrackingDecision(
            capture = true,
            identityMode = AttriaxTrackingIdentityMode.IDENTIFIED,
            deferNetwork = false,
        )
        val ANONYMOUS = AttriaxTrackingDecision(
            capture = true,
            identityMode = AttriaxTrackingIdentityMode.ANONYMOUS,
            deferNetwork = false,
        )
        val WITHHELD = AttriaxTrackingDecision(
            capture = false,
            identityMode = AttriaxTrackingIdentityMode.WITHHELD,
            deferNetwork = false,
        )
    }
}

/**
 * Wire-value mapping for [AttriaxGdprConsentState].
 *
 * The api `AppUserGdprConsentState` enum (app-user-gdpr-consent.entity.ts:13-18)
 * uses snake_case string values — critically `not_required`, NOT `notRequired`.
 * These are the exact strings sent on the consent write DTO `state` field and
 * received on the consent status echo. Storage uses the same tokens.
 */
object AttriaxConsentStateWire {
    const val UNKNOWN = "unknown"
    const val NOT_REQUIRED = "not_required"
    const val PENDING = "pending"
    const val GRANTED = "granted"

    fun toWire(state: AttriaxGdprConsentState): String = when (state) {
        AttriaxGdprConsentState.UNKNOWN -> UNKNOWN
        AttriaxGdprConsentState.NOT_REQUIRED -> NOT_REQUIRED
        AttriaxGdprConsentState.PENDING -> PENDING
        AttriaxGdprConsentState.GRANTED -> GRANTED
    }

    fun fromWire(raw: String?): AttriaxGdprConsentState = when (raw) {
        NOT_REQUIRED -> AttriaxGdprConsentState.NOT_REQUIRED
        PENDING -> AttriaxGdprConsentState.PENDING
        GRANTED -> AttriaxGdprConsentState.GRANTED
        else -> AttriaxGdprConsentState.UNKNOWN
    }
}
