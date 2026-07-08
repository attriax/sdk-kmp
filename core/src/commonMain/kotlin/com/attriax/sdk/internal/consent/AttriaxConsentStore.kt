package com.attriax.sdk.internal.consent

import com.attriax.sdk.internal.AttriaxIdGenerator
import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.json.Json

/** Snapshot of the persisted consent decision (PARITY §5, row C2). */
data class AttriaxStoredConsent(
    val state: AttriaxGdprConsentState,
    val values: AttriaxGdprConsentValues?,
    val countryCode: String?,
    val regionSource: String?,
    val checkedAtIso: String?,
    val pendingSync: Boolean,
)

/**
 * Persists the GDPR consent decision + the SDK-generated `consentId` to the
 * [KeyValueStore] (PARITY §5, row C2). The consentId is generated ONCE with the
 * same id generator used for device / queued-request ids and reused for every
 * consent check/upsert, so the backend can correlate a device's consent history
 * WITHOUT the SDK ever sending a device or user identifier on the consent body.
 */
class AttriaxConsentStore(private val store: KeyValueStore) {

    /** Return the stored consent snapshot, or null when nothing has been persisted. */
    fun read(): AttriaxStoredConsent? {
        val raw = store.getString(KEY_CONSENT) ?: return null
        return try {
            val obj = Json.decodeObject(raw)
            val valuesObj = obj["values"] as? Map<*, *>
            val values = valuesObj?.let {
                AttriaxGdprConsentValues(
                    analytics = (it["analytics"] as? Boolean) ?: false,
                    attribution = (it["attribution"] as? Boolean) ?: false,
                    adEvents = (it["adEvents"] as? Boolean) ?: false,
                )
            }
            AttriaxStoredConsent(
                state = AttriaxConsentStateWire.fromWire(obj["state"] as? String),
                values = values,
                countryCode = obj["countryCode"] as? String,
                regionSource = obj["regionSource"] as? String,
                checkedAtIso = obj["checkedAt"] as? String,
                pendingSync = (obj["pendingSync"] as? Boolean) ?: false,
            )
        } catch (e: Exception) {
            // Corrupt consent blob: drop it and fall back to the default state.
            store.remove(KEY_CONSENT)
            null
        }
    }

    /** Persist [data], or clear the stored blob when [data] is null. */
    fun write(data: AttriaxStoredConsent?) {
        if (data == null) {
            store.remove(KEY_CONSENT)
            return
        }
        val body = LinkedHashMap<String, Any?>()
        body["state"] = AttriaxConsentStateWire.toWire(data.state)
        data.values?.let {
            body["values"] = linkedMapOf<String, Any?>(
                "analytics" to it.analytics,
                "attribution" to it.attribution,
                "adEvents" to it.adEvents,
            )
        }
        data.countryCode?.let { body["countryCode"] = it }
        data.regionSource?.let { body["regionSource"] = it }
        data.checkedAtIso?.let { body["checkedAt"] = it }
        body["pendingSync"] = data.pendingSync
        store.putString(KEY_CONSENT, Json.encode(body))
    }

    /** Load the persisted consentId, generating + persisting one on first use. */
    fun ensureConsentId(): String {
        store.getString(KEY_CONSENT_ID)?.let { return it }
        val generated = AttriaxIdGenerator.generate()
        store.putString(KEY_CONSENT_ID, generated)
        return generated
    }

    /** Clear both the consent decision and the consentId (reset()/erase()). */
    fun clear() {
        store.remove(KEY_CONSENT)
        store.remove(KEY_CONSENT_ID)
    }

    companion object {
        const val KEY_CONSENT = "attriax.gdpr_consent"
        const val KEY_CONSENT_ID = "attriax.gdpr_consent_id"
    }
}
