package com.attriax.sdk.internal.skan

import com.attriax.sdk.AttriaxSkanCoarseValue
import com.attriax.sdk.AttriaxSkanConfig
import com.attriax.sdk.AttriaxSkanState
import com.attriax.sdk.AttriaxSkanUpdateResult
import com.attriax.sdk.AttriaxSkanUpdateStatus

/** SKAN fine conversion value is a 6-bit field: 0..63 (mirrors StoreKit). */
private const val SKAN_FINE_VALUE_MIN = 0
private const val SKAN_FINE_VALUE_MAX = 63

/**
 * Derive the coarse value from a fine value.
 *
 * Faithful port of the Flutter reference `deriveSkanCoarseValue`
 * (`internal/skan/attriax_skan_rules.dart:55`): `>=40 → high`, `>=20 → medium`,
 * else `low`.
 */
internal fun deriveSkanCoarseValue(fineValue: Int): AttriaxSkanCoarseValue = when {
    fineValue >= 40 -> AttriaxSkanCoarseValue.HIGH
    fineValue >= 20 -> AttriaxSkanCoarseValue.MEDIUM
    else -> AttriaxSkanCoarseValue.LOW
}

/**
 * The greater of two coarse values by severity (`low < medium < high`).
 *
 * Faithful port of the Flutter reference `maxSkanCoarseValue`
 * (`attriax_skan_rules.dart:66`) — `null` is treated as absent.
 */
internal fun maxSkanCoarseValue(
    current: AttriaxSkanCoarseValue?,
    next: AttriaxSkanCoarseValue?,
): AttriaxSkanCoarseValue? = when {
    current == null -> next
    next == null -> current
    current.ordinal >= next.ordinal -> current
    else -> next
}

/**
 * Pure SKAdNetwork conversion-value engine — the passthrough core.
 *
 * Mirrors the public `updateConversionValue` path of the Flutter reference
 * `AttriaxSkanConversionUpdater` (`internal/skan/attriax_skan_conversion_updater.dart`):
 * it validates the request, applies the MONOTONIC rules (the fine value never
 * decreases, the coarse value is maxed, `lockWindow` is sticky), and only invokes the
 * on-device StoreKit seam when the value actually advances. The dashboard-managed
 * LOCAL rules engine (window1/2/3 schema resolution, retention milestones, event
 * augmentation) that Flutter drives from the app-open runtime configuration is NOT
 * ported here — the KMP core is a StoreKit passthrough with wrapper/host-driven
 * conversion values.
 *
 * Support is probed via [supported] (the platform seam); off-iOS it is `false`, so
 * [currentState] is `null` and [updateConversionValue] returns
 * [AttriaxSkanUpdateStatus.NOT_SUPPORTED] without touching [performUpdate].
 */
internal class AttriaxSkanEngine(
    private val config: AttriaxSkanConfig,
    private val supported: () -> Boolean,
    private val performUpdate: (
        fineValue: Int,
        coarseValue: AttriaxSkanCoarseValue,
        lockWindow: Boolean,
    ) -> AttriaxSkanUpdateResult,
) {
    private var state: AttriaxSkanState = AttriaxSkanState(enabled = config.enabled)

    /** The locally tracked SKAN state, or `null` when SKAN is unsupported (off-iOS). */
    val currentState: AttriaxSkanState?
        get() = if (supported()) state else null

    fun updateConversionValue(
        fineValue: Int,
        coarseValue: AttriaxSkanCoarseValue?,
        lockWindow: Boolean,
    ): AttriaxSkanUpdateResult {
        // Ordering mirrors Flutter: unsupported → disabled → invalid value → apply.
        if (!supported()) {
            return AttriaxSkanUpdateResult(
                status = AttriaxSkanUpdateStatus.NOT_SUPPORTED,
                message = "SKAdNetwork updates are only supported on iOS.",
            )
        }
        if (!state.enabled) {
            return AttriaxSkanUpdateResult(
                status = AttriaxSkanUpdateStatus.DISABLED,
                message = "SKAdNetwork is disabled for this SDK instance.",
                fineValue = state.fineValue,
                coarseValue = state.coarseValue,
                lockWindow = state.lockWindow,
            )
        }
        if (fineValue < SKAN_FINE_VALUE_MIN || fineValue > SKAN_FINE_VALUE_MAX) {
            return AttriaxSkanUpdateResult(
                status = AttriaxSkanUpdateStatus.INVALID_VALUE,
                message = "fineValue must be between 0 and 63.",
                fineValue = state.fineValue,
                coarseValue = state.coarseValue,
                lockWindow = state.lockWindow,
            )
        }

        val currentFine = state.fineValue
        val nextFine = if (currentFine == null) fineValue else maxOf(fineValue, currentFine)
        val nextCoarse = maxSkanCoarseValue(
            state.coarseValue,
            coarseValue ?: deriveSkanCoarseValue(nextFine),
        )
        val nextLock = state.lockWindow || lockWindow

        // The conversion value does not advance → no native bridge call is needed.
        if (nextFine == currentFine &&
            nextCoarse == state.coarseValue &&
            nextLock == state.lockWindow
        ) {
            return AttriaxSkanUpdateResult(
                status = AttriaxSkanUpdateStatus.ALREADY_AT_OR_ABOVE_VALUE,
                message = "The requested conversion value does not advance the stored SKAN state.",
                fineValue = state.fineValue,
                coarseValue = state.coarseValue,
                lockWindow = state.lockWindow,
            )
        }

        val resolvedCoarse = nextCoarse ?: deriveSkanCoarseValue(nextFine)
        val bridge = performUpdate(nextFine, resolvedCoarse, nextLock)

        return if (bridge.status == AttriaxSkanUpdateStatus.UPDATED ||
            bridge.status == AttriaxSkanUpdateStatus.SKIPPED
        ) {
            // Persist the advanced state only when the bridge accepted the update.
            state = state.copy(
                fineValue = nextFine,
                coarseValue = resolvedCoarse,
                lockWindow = nextLock,
            )
            AttriaxSkanUpdateResult(
                status = bridge.status,
                message = bridge.message,
                fineValue = nextFine,
                coarseValue = resolvedCoarse,
                lockWindow = nextLock,
            )
        } else {
            // Bridge failure — surface it without advancing the stored state.
            bridge
        }
    }
}
