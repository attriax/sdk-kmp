package com.attriax.sdk

/**
 * SKAdNetwork coarse conversion value.
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
 * Outcome of a SKAdNetwork conversion-value update.
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
 * Result of [AttriaxSkan.updateConversionValue].
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
 * Locally tracked SKAdNetwork state — the passthrough subset.
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
 * SKAdNetwork configuration knob.
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

/**
 * The project's configured SKAN conversion-value rules, pulled from the backend by
 * [AttriaxSkan.fetchConversionConfig].
 *
 * Mirrors the api `SdkCvConfigResponse` (`cv-rule-transformer.ts`):
 * `{ schemaVersion, schemaUpdatedAt, enabled, rules[], disclaimer }`. The SDK does
 * NOT compose a conversion value from these rules on its own (that needs the host's
 * per-event/revenue state); it surfaces the ordered rule list so the host can
 * evaluate it and call [AttriaxSkan.updateConversionValue].
 */
data class AttriaxSkanConversionConfig(
    val schemaVersion: Int? = null,
    val schemaUpdatedAt: String? = null,
    val enabled: Boolean,
    val rules: List<AttriaxSkanCvRule>,
    val disclaimer: String? = null,
)

/**
 * One SKAN CV rule: "when [whenEvent] (and its [whenConditions]) is satisfied, group
 * [groupId] contributes [bitContribution] (`rank << startBit`) to the fine value, and
 * the update should adopt [coarseValue] / [lockWindow]."
 */
data class AttriaxSkanCvRule(
    val id: String,
    val groupId: String? = null,
    val groupDisplayName: String? = null,
    val startBit: Int,
    val bitCount: Int,
    val rank: Int,
    val bitContribution: Int,
    val whenEvent: String,
    val whenConditions: List<AttriaxSkanCvCondition>,
    val whenRevenue: AttriaxSkanCvRevenueCondition? = null,
    val coarseValue: AttriaxSkanCoarseValue? = null,
    val lockWindow: Boolean = false,
)

/** A parameter condition that must ALSO hold for a rule to fire. */
data class AttriaxSkanCvCondition(
    val paramKey: String,
    val operator: String,
    /** Opaque comparison value (string / number / bool), preserved type-faithfully. */
    val value: AttriaxSkanCvValue? = null,
)

/** Convenience view of a `__revenue` condition on a rule (or null). */
data class AttriaxSkanCvRevenueCondition(
    val operator: String,
    val value: AttriaxSkanCvValue? = null,
)

/**
 * A JSON-scalar condition value (string / number / bool), kept type-preserving so the
 * host can compare against its own event params. Mirrors the Swift facade's
 * `AttriaxSkanCvValue` enum.
 */
sealed class AttriaxSkanCvValue {
    data class StringValue(val value: String) : AttriaxSkanCvValue()
    data class NumberValue(val value: Double) : AttriaxSkanCvValue()
    data class BoolValue(val value: Boolean) : AttriaxSkanCvValue()
}
