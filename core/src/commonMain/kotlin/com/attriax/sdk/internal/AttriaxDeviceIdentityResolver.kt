package com.attriax.sdk.internal

/** Device-id source slugs (PARITY §2 / row D4). */
object AttriaxDeviceIdSource {
    const val ANDROID_SSAID = "android_ssaid"
    const val ANDROID_GAID = "android_gaid"
    const val PERSISTENT_STORAGE = "persistent_storage"
}

/** A resolved device identity: the value plus the source slug it came from. */
data class ResolvedDeviceId(
    val value: String,
    val source: String,
    /** True when this is the generated persistent-storage fallback, not a native id. */
    val isFallback: Boolean = false,
)

/**
 * Resolves the preferred device id + source (PARITY §2, rows D1–D4).
 *
 * Precedence mirrors the Flutter reference
 * `attriax_device_identity_resolver.dart` (Android branch):
 *   1. Settings.Secure ANDROID_ID (SSAID)  → source `android_ssaid`
 *   2. Advertising ID (GAID)               → source `android_gaid`
 *   3. otherwise the persistent-storage fallback id → source `persistent_storage`
 *
 * Empty strings are treated as absent (`emptyToNull`). When
 * `collectAdvertisingId == false`, the GAID candidate is never consulted — the
 * Android [DeviceIdSources] implementation returns null for it, matching
 * Flutter's advertising-id gating.
 *
 * Pure: given a [DeviceIdSources] port and a fallback id, resolution is
 * deterministic and unit-testable with fake sources.
 */
class AttriaxDeviceIdentityResolver(
    private val sources: DeviceIdSources,
    private val collectAdvertisingId: Boolean,
) {
    /**
     * @param fallbackDeviceId a stable, already-persisted (or freshly generated)
     *   id used when no native source is available.
     */
    fun resolve(fallbackDeviceId: String): ResolvedDeviceId {
        val ssaid = sources.androidSsaid().emptyToNull()
        if (ssaid != null) {
            return ResolvedDeviceId(value = ssaid, source = AttriaxDeviceIdSource.ANDROID_SSAID)
        }

        if (collectAdvertisingId) {
            val gaid = sources.advertisingId().emptyToNull()
            if (gaid != null) {
                return ResolvedDeviceId(value = gaid, source = AttriaxDeviceIdSource.ANDROID_GAID)
            }
        }

        return ResolvedDeviceId(
            value = fallbackDeviceId,
            source = AttriaxDeviceIdSource.PERSISTENT_STORAGE,
            isFallback = true,
        )
    }

    private fun String?.emptyToNull(): String? =
        this?.takeIf { it.isNotEmpty() }
}
