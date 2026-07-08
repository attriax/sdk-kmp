package com.attriax.sdk.internal.installreferrer

/**
 * The Google Play install-referrer signal captured at first launch (PARITY §3,
 * app-open enrichment). Mirrors the four wire fields the app-open DTO accepts
 * top-level (api `SdkV1OpenDto`): the raw referrer string plus the three Play
 * `ReferrerDetails` timestamps/flags.
 *
 * All fields are nullable: a cache hit re-attaches only [rawReferrer] (the sole
 * value persisted across launches — matching the Flutter reference, which caches
 * only the raw string), while a fresh Play-client fetch also carries the
 * timestamps.
 */
data class AttriaxInstallReferrerDetails(
    val rawReferrer: String? = null,
    val installBeginTimestampSeconds: Long? = null,
    val referrerClickTimestampSeconds: Long? = null,
    val googlePlayInstantParam: Boolean? = null,
) {
    /** True when a usable (non-blank) referrer string is present. */
    fun hasReferrer(): Boolean = !rawReferrer.isNullOrEmpty()
}

/**
 * Blocking, one-shot seam over the Google Play Install Referrer API. The real
 * implementation ([com.attriax.sdk.android.AttriaxPlayInstallReferrerProvider])
 * lives in the android layer; the pure engine + tests use fakes or
 * [Unavailable], keeping the capture policy
 * ([AttriaxInstallReferrerCoordinator]) framework-free and unit-tested.
 *
 * [fetch] is invoked OFF the init thread (on the engine's flush executor) because
 * the Play client connection is I/O. Returning `null`/empty is a first-class,
 * expected outcome (service unavailable, non-Play build, JVM) — the coordinator
 * treats it as "no referrer yet" and simply attaches nothing.
 */
interface AttriaxInstallReferrerProvider {
    /** Blocking fetch of the Play install referrer; `null` when unavailable. */
    fun fetch(): AttriaxInstallReferrerDetails?

    /**
     * Sentinel provider for environments with no Play install-referrer capability
     * (the pure engine, non-Play distributions). [AttriaxInstallReferrerCoordinator]
     * short-circuits capture entirely when the provider is this object.
     */
    object Unavailable : AttriaxInstallReferrerProvider {
        override fun fetch(): AttriaxInstallReferrerDetails? = null
    }
}
