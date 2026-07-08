package com.attriax.sdk

/**
 * SKAdNetwork coarse conversion value (Epic 8.5 / Epic 12 CV modeling).
 *
 * Mirrors the Flutter reference `AttriaxSkanCoarseValue`
 * (`attriax_flutter_platform_interface/lib/src/types.dart:76`). The [wireValue]s
 * match Flutter's `enum.name` (`low`/`medium`/`high`) used on the SKAN state /
 * update-result JSON and passed to StoreKit's `updatePostbackConversionValue`
 * `coarseValue:` argument (via the on-device seam, deferred to iosMain).
 */
enum class AttriaxSkanCoarseValue(val wireValue: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

/**
 * Outcome of a SKAdNetwork conversion-value update (Epic 8.5).
 *
 * Mirrors the Flutter reference `AttriaxSkanUpdateStatus`
 * (`types.dart` enum) 1:1; [wireValue]s are the snake_case strings Flutter emits
 * (`_skanUpdateStatusToJson`). SKAN is Apple-only, so on every currently-built
 * target ([com.attriax.sdk.internal.attriaxSkanSupported] is `false`) the on-device
 * seam short-circuits to [NOT_SUPPORTED]; the future iosMain actual returns the
 * real StoreKit outcome.
 */
enum class AttriaxSkanUpdateStatus(val wireValue: String) {
    UPDATED("updated"),
    SKIPPED("skipped"),
    ALREADY_AT_OR_ABOVE_VALUE("already_at_or_above_value"),
    INVALID_VALUE("invalid_value"),
    DISABLED("disabled"),
    NOT_SUPPORTED("not_supported"),
    ERROR("error"),
}

/**
 * Result of [AttriaxSkan.updateConversionValue] (Epic 8.5).
 *
 * Mirrors the Flutter reference `AttriaxSkanUpdateResult`
 * (`types_skan.dart:470`) — the public-facing subset: the resolved [status], an
 * optional human [message], and the [fineValue]/[coarseValue]/[lockWindow] the SDK
 * settled on. The Flutter type additionally carries a nested `state`; the KMP
 * passthrough core exposes state separately via [AttriaxSkan.state] and does not
 * embed it here.
 */
data class AttriaxSkanUpdateResult(
    val status: AttriaxSkanUpdateStatus,
    val message: String? = null,
    val fineValue: Int? = null,
    val coarseValue: AttriaxSkanCoarseValue? = null,
    val lockWindow: Boolean? = null,
)

/**
 * Locally tracked SKAdNetwork state (Epic 8.5) — the passthrough subset.
 *
 * Mirrors the public-facing fields of the Flutter reference `AttriaxSkanState`
 * (`types_skan.dart:337`): whether SKAN is [enabled] plus the monotonic
 * [fineValue]/[coarseValue]/[lockWindow] the SDK has pushed to StoreKit. The
 * Flutter state additionally models the dashboard-managed schema, retention days
 * and purchase/ad counters that drive the LOCAL conversion-value rules engine; that
 * rules engine is delivered via the app-open runtime configuration and is NOT
 * ported to the KMP passthrough core (see [AttriaxSkan]).
 */
data class AttriaxSkanState(
    val enabled: Boolean,
    val fineValue: Int? = null,
    val coarseValue: AttriaxSkanCoarseValue? = null,
    val lockWindow: Boolean = false,
)

/**
 * SKAdNetwork configuration knob (Epic 8.5).
 *
 * Mirrors the Flutter reference `AttriaxSkanConfig`
 * (`types_skan.dart:3`): [enabled] (default `true`) gates on-device conversion-value
 * updates; [registerFirstLaunchValue] (default `true`) mirrors Flutter's first-launch
 * install-value registration flag. NOTE: `registerFirstLaunchValue` is INERT in the
 * KMP passthrough core — the first-launch auto-registration lives in Flutter's local
 * SKAN rules engine, which the KMP core does not port. It is retained here for name
 * parity and forward-compatibility once the schema engine lands.
 */
data class AttriaxSkanConfig(
    val enabled: Boolean = true,
    val registerFirstLaunchValue: Boolean = true,
)
