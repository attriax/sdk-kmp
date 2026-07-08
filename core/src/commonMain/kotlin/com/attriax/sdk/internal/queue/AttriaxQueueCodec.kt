package com.attriax.sdk.internal.queue

import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.request.AttriaxApiRequest
import com.attriax.sdk.internal.request.AttriaxEndpoints

/**
 * Pure (de)serialization of the persisted queue, including the legacy
 * field-normalization boundary (PARITY §10, row FR1;
 * Flutter `request_json_codec.dart:34-76`).
 *
 * At the deserialization boundary:
 *  - `appToken` → `projectToken` rename (older builds stored the token as
 *    `appToken`; if `projectToken` is absent and `appToken` present, rename and
 *    drop `appToken`).
 *  - `identify` kind → `user` handler alias.
 *
 * Corruption handling (row Q1):
 *  - a whole-payload that is not a JSON array → clear the queue.
 *  - individual invalid entries → dropped (the rest are kept).
 */
object AttriaxQueueCodec {

    /** Result of decoding the persisted queue payload. */
    data class DecodeResult(
        val queue: List<AttriaxQueuedRequest>,
        /** True when the entire payload was unparseable and should be cleared. */
        val clearedWholePayload: Boolean = false,
        /** Count of individually-invalid entries that were dropped. */
        val droppedEntryCount: Int = 0,
    )

    fun encode(queue: List<AttriaxQueuedRequest>): String {
        val list = queue.map { encodeEntry(it) }
        return Json.encode(list)
    }

    private fun encodeEntry(entry: AttriaxQueuedRequest): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["id"] = entry.id
        map["kind"] = entry.request.kind
        map["body"] = entry.request.body
        map["createdAt"] = entry.createdAtMs
        map["attemptCount"] = entry.attemptCount
        entry.lastAttemptAtMs?.let { map["lastAttemptAt"] = it }
        entry.lastErrorClass?.let { map["lastErrorClass"] = it }
        entry.lastHttpStatusCode?.let { map["lastHttpStatusCode"] = it }
        entry.nextRetryAtMs?.let { map["nextRetryAt"] = it }
        return map
    }

    fun decode(rawPayload: String?): DecodeResult {
        if (rawPayload.isNullOrEmpty()) return DecodeResult(emptyList())

        val list = try {
            Json.decodeArray(rawPayload)
        } catch (e: Exception) {
            // Invalid whole payload → clear (row Q1).
            return DecodeResult(emptyList(), clearedWholePayload = true)
        }

        val queue = ArrayList<AttriaxQueuedRequest>()
        var dropped = 0
        for (element in list) {
            @Suppress("UNCHECKED_CAST")
            val obj = element as? Map<String, Any?>
            if (obj == null) {
                dropped++
                continue
            }
            val entry = try {
                decodeEntry(obj)
            } catch (e: Exception) {
                null
            }
            if (entry == null) dropped++ else queue.add(entry)
        }

        return DecodeResult(queue, droppedEntryCount = dropped)
    }

    private fun decodeEntry(json: Map<String, Any?>): AttriaxQueuedRequest {
        val id = json["id"] as? String ?: throw IllegalArgumentException("missing id")
        val rawKind = json["kind"] as? String ?: throw IllegalArgumentException("missing kind")

        @Suppress("UNCHECKED_CAST")
        val rawBody = (json["body"] as? Map<String, Any?>) ?: emptyMap()

        val (kind, body) = normalize(rawKind, rawBody)
        val request = AttriaxApiRequest(kind = kind, path = pathForKind(kind), body = body)

        return AttriaxQueuedRequest(
            id = id,
            request = request,
            createdAtMs = longOf(json["createdAt"]) ?: 0L,
            attemptCount = intOf(json["attemptCount"]) ?: 0,
            lastAttemptAtMs = longOf(json["lastAttemptAt"]),
            lastErrorClass = json["lastErrorClass"] as? String,
            lastHttpStatusCode = intOf(json["lastHttpStatusCode"]),
            nextRetryAtMs = longOf(json["nextRetryAt"]),
        )
    }

    /**
     * Legacy normalization at the restore boundary (row FR1). Public so it can
     * be unit-tested directly against the parity contract.
     */
    fun normalize(rawKind: String, rawBody: Map<String, Any?>): Pair<String, Map<String, Any?>> {
        // Kind alias: 'identify' → 'user'.
        val kind = if (rawKind == AttriaxApiRequest.LEGACY_KIND_IDENTIFY) {
            AttriaxApiRequest.KIND_USER
        } else {
            rawKind
        }
        return kind to migrateLegacyProjectToken(rawBody)
    }

    private fun migrateLegacyProjectToken(body: Map<String, Any?>): Map<String, Any?> {
        val existing = body[AttriaxApiRequest.FIELD_PROJECT_TOKEN] as? String
        if (existing != null && existing.isNotEmpty()) return body

        val legacy = body[AttriaxApiRequest.FIELD_LEGACY_APP_TOKEN] as? String
        if (legacy == null || legacy.isEmpty()) return body

        val migrated = LinkedHashMap(body)
        migrated[AttriaxApiRequest.FIELD_PROJECT_TOKEN] = legacy
        migrated.remove(AttriaxApiRequest.FIELD_LEGACY_APP_TOKEN)
        return migrated
    }

    /** Map a persisted kind back to its wire endpoint. */
    fun pathForKind(kind: String): String = when (kind) {
        AttriaxApiRequest.KIND_OPEN -> AttriaxEndpoints.OPEN
        AttriaxApiRequest.KIND_TRACK_EVENT -> AttriaxEndpoints.EVENTS
        AttriaxApiRequest.KIND_TRACK_SESSION -> AttriaxEndpoints.SESSIONS
        AttriaxApiRequest.KIND_USER -> AttriaxEndpoints.USERS
        AttriaxApiRequest.KIND_TRACK_NOTIFICATION -> AttriaxEndpoints.NOTIFICATIONS
        AttriaxApiRequest.KIND_TRACK_CRASH -> AttriaxEndpoints.CRASHES
        AttriaxApiRequest.KIND_RESOLVE_DEEP_LINK -> AttriaxEndpoints.DEEP_LINKS_RESOLVE
        AttriaxApiRequest.KIND_CREATE_DYNAMIC_LINK -> AttriaxEndpoints.DYNAMIC_LINKS
        AttriaxApiRequest.KIND_REGISTER_UNINSTALL_TOKEN -> AttriaxEndpoints.UNINSTALL_TOKENS
        else -> throw IllegalArgumentException("Unsupported Attriax request kind: $kind")
    }

    private fun longOf(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun intOf(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
