package com.attriax.sdk.internal

/** Device-id source slugs (PARITY §2 / row D4). */
object AttriaxDeviceIdSource {
    const val ANDROID_SSAID = "android_ssaid"
    const val ANDROID_GAID = "android_gaid"
    const val IOS_IDFV = "ios_idfv"
    const val IOS_IDFA = "ios_idfa"
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
 * Precedence mirrors the Flutter reference `attriax_device_identity_resolver.dart`:
 *   1. Apple `identifierForVendor` (IDFV) → source `ios_idfv`   (iOS branch)
 *   2. Settings.Secure ANDROID_ID (SSAID) → source `android_ssaid` (Android branch)
 *   3. Advertising ID (GAID / IDFA)       → source [advertisingIdSource]
 *      (`android_gaid` on Android, `ios_idfa` on Apple)
 *   4. otherwise the persistent-storage fallback id → source `persistent_storage`
 *
 * A given device only ever supplies ONE primary native id — IDFV on Apple, SSAID on
 * Android — so steps 1–2 are mutually exclusive in practice; the platform
 * [DeviceIdSources] returns null for the other. Empty strings are treated as absent
 * (`emptyToNull`). When `collectAdvertisingId == false`, the advertising candidate is
 * never consulted, matching Flutter's advertising-id gating.
 *
 * The advertising-id SLUG differs per platform (GAID vs IDFA) while the id itself
 * flows through the single [DeviceIdSources.advertisingId] seam, so the factory
 * passes [advertisingIdSource] (defaulting to `android_gaid`; the Apple factory
 * passes `ios_idfa`).
 *
 * Pure: given a [DeviceIdSources] port and a fallback id, resolution is
 * deterministic and unit-testable with fake sources.
 */
class AttriaxDeviceIdentityResolver(
    private val sources: DeviceIdSources,
    private val collectAdvertisingId: Boolean,
    private val advertisingIdSource: String = AttriaxDeviceIdSource.ANDROID_GAID,
) {
    /**
     * @param fallbackDeviceId a stable, already-persisted (or freshly generated)
     *   id used when no native source is available.
     */
    fun resolve(fallbackDeviceId: String): ResolvedDeviceId {
        val idfv = sources.iosIdfv().emptyToNull()
        if (idfv != null) {
            return ResolvedDeviceId(value = idfv, source = AttriaxDeviceIdSource.IOS_IDFV)
        }

        val ssaid = sources.androidSsaid().emptyToNull()
        if (ssaid != null) {
            return ResolvedDeviceId(value = ssaid, source = AttriaxDeviceIdSource.ANDROID_SSAID)
        }

        if (collectAdvertisingId) {
            val advertisingId = sources.advertisingId().emptyToNull()
            if (advertisingId != null) {
                return ResolvedDeviceId(value = advertisingId, source = advertisingIdSource)
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
