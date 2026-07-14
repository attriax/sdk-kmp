package com.attriax.sdk.internal.skan

import com.attriax.sdk.AttriaxSkanCoarseValue
import com.attriax.sdk.AttriaxSkanConversionConfig
import com.attriax.sdk.AttriaxSkanCvCondition
import com.attriax.sdk.AttriaxSkanCvRevenueCondition
import com.attriax.sdk.AttriaxSkanCvRule
import com.attriax.sdk.AttriaxSkanCvValue

/**
 * Decodes the api `SdkCvConfigResponse` wire shape (`cv-rule-transformer.ts`)
 * into [AttriaxSkanConversionConfig].
 *
 * Input is the already-envelope-unwrapped, [com.attriax.sdk.internal.json.Json]
 * -decoded tree (`Map`/`List`/`String`/`Long`/`Double`/`Boolean`/null). Pure and
 * fully null-safe: a missing/wrong-typed field degrades to a sensible default rather
 * than throwing, so a partially-shaped payload never crashes the best-effort pull.
 */
internal object AttriaxSkanCvConfigDecoder {

    /** Reserved parameter key that revenue conditions reference (api `REVENUE_PARAM_KEY`). */
    private const val REVENUE_PARAM_KEY = "__revenue"

    fun decode(decoded: Any?): AttriaxSkanConversionConfig? {
        val root = decoded as? Map<*, *> ?: return null
        val rules = (root["rules"] as? List<*>).orEmptyList()
            .mapNotNull { decodeRule(it) }
        return AttriaxSkanConversionConfig(
            schemaVersion = root["schemaVersion"].asIntOrNull(),
            schemaUpdatedAt = root["schemaUpdatedAt"] as? String,
            enabled = root["enabled"] as? Boolean ?: false,
            rules = rules,
            disclaimer = root["disclaimer"] as? String,
        )
    }

    private fun decodeRule(raw: Any?): AttriaxSkanCvRule? {
        val map = raw as? Map<*, *> ?: return null
        val id = map["id"] as? String ?: return null
        val whenEvent = map["whenEvent"] as? String ?: return null
        val conditions = (map["whenConditions"] as? List<*>).orEmptyList()
            .mapNotNull { decodeCondition(it) }
        return AttriaxSkanCvRule(
            id = id,
            groupId = map["groupId"] as? String,
            groupDisplayName = map["groupDisplayName"] as? String,
            startBit = map["startBit"].asIntOrNull() ?: 0,
            bitCount = map["bitCount"].asIntOrNull() ?: 0,
            rank = map["rank"].asIntOrNull() ?: 0,
            bitContribution = map["bitContribution"].asIntOrNull() ?: 0,
            whenEvent = whenEvent,
            whenConditions = conditions,
            whenRevenue = decodeRevenue(map["whenRevenue"]),
            coarseValue = decodeCoarse(map["coarseValue"] as? String),
            lockWindow = map["lockWindow"] as? Boolean ?: false,
        )
    }

    private fun decodeCondition(raw: Any?): AttriaxSkanCvCondition? {
        val map = raw as? Map<*, *> ?: return null
        val paramKey = map["paramKey"] as? String ?: return null
        val operator = map["operator"] as? String ?: return null
        return AttriaxSkanCvCondition(
            paramKey = paramKey,
            operator = operator,
            value = decodeValue(map["value"]),
        )
    }

    private fun decodeRevenue(raw: Any?): AttriaxSkanCvRevenueCondition? {
        val map = raw as? Map<*, *> ?: return null
        val operator = map["operator"] as? String ?: return null
        return AttriaxSkanCvRevenueCondition(
            operator = operator,
            value = decodeValue(map["value"]),
        )
    }

    private fun decodeValue(raw: Any?): AttriaxSkanCvValue? = when (raw) {
        is String -> AttriaxSkanCvValue.StringValue(raw)
        is Boolean -> AttriaxSkanCvValue.BoolValue(raw)
        is Long -> AttriaxSkanCvValue.NumberValue(raw.toDouble())
        is Int -> AttriaxSkanCvValue.NumberValue(raw.toDouble())
        is Double -> AttriaxSkanCvValue.NumberValue(raw)
        else -> null
    }

    private fun decodeCoarse(value: String?): AttriaxSkanCoarseValue? = when (value) {
        "low" -> AttriaxSkanCoarseValue.LOW
        "medium" -> AttriaxSkanCoarseValue.MEDIUM
        "high" -> AttriaxSkanCoarseValue.HIGH
        else -> null
    }

    private fun Any?.asIntOrNull(): Int? = when (this) {
        is Int -> this
        is Long -> this.toInt()
        is Double -> this.toInt()
        else -> null
    }

    private fun List<*>?.orEmptyList(): List<*> = this ?: emptyList<Any?>()
}
