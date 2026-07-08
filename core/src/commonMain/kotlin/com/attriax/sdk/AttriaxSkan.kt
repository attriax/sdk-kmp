package com.attriax.sdk

/**
 * SKAdNetwork (SKAN) helpers exposed as `attriax.skan` (Epic 8.5).
 *
 * Mirrors the Flutter reference `AttriaxSkan` (`attriax_skan.dart:11`): a [state]
 * getter over the locally tracked SKAN state and a manual [updateConversionValue].
 *
 * SKAN is an Apple-only StoreKit framework. On every currently-built target
 * (android/jvm/native) the platform seam reports SKAN unsupported, so [state] is
 * `null` and [updateConversionValue] returns
 * [AttriaxSkanUpdateStatus.NOT_SUPPORTED]; the future iosMain actual (deferred to
 * Mac) wires the real `SKAdNetwork.updatePostbackConversionValue` calls.
 *
 * Scope note: the KMP core is a StoreKit PASSTHROUGH for conversion values. The
 * dashboard-managed local rules engine (window1/2/3 schema resolution, retention
 * milestones, event-driven auto CV updates) that the Flutter reference drives from
 * the app-open runtime configuration is NOT ported; there is no separate backend CV
 * fetch endpoint — the SDK resolves values from that app-open-delivered schema and
 * pushes them to StoreKit, and here the host/wrapper supplies the resolved value.
 */
class AttriaxSkan internal constructor(private val engine: Attriax) {

    /**
     * Latest locally tracked SKAdNetwork state, or `null` on platforms where SKAN is
     * unsupported (every currently-built target). Mirrors `AttriaxSkan.state`.
     */
    val state: AttriaxSkanState? get() = engine.skanState

    /**
     * Manually update the current SKAdNetwork conversion value (Epic 8.5).
     *
     * Mirrors `AttriaxSkan.updateConversionValue`. The [fineValue] (0..63) is applied
     * MONOTONICALLY (it never decreases); [coarseValue] defaults to the value derived
     * from the fine value and is likewise maxed; [lockWindow] is sticky. Only a value
     * that actually advances reaches StoreKit (via the on-device seam). Returns the
     * resolved [AttriaxSkanUpdateResult] — [AttriaxSkanUpdateStatus.NOT_SUPPORTED] on
     * every currently-built target.
     */
    fun updateConversionValue(
        fineValue: Int,
        coarseValue: AttriaxSkanCoarseValue? = null,
        lockWindow: Boolean = false,
    ): AttriaxSkanUpdateResult =
        engine.updateSkanConversionValue(
            fineValue = fineValue,
            coarseValue = coarseValue,
            lockWindow = lockWindow,
        )
}
