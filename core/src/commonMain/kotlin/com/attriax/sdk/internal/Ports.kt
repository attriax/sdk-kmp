package com.attriax.sdk.internal

/**
 * Narrow ports (hexagonal boundaries) behind which all platform-specific I/O lives.
 *
 * Every port here is a plain Kotlin interface with no platform framework types in
 * its signature, so the pure engine and its tests depend only on these
 * abstractions. The platform implementations (SharedPreferences, OkHttp,
 * ConnectivityManager, Settings.Secure / Play Services) live under
 * `com.attriax.sdk.android` and are never touched by the shared tests.
 */

/** Key/value persistence port (backed by SharedPreferences on device). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun remove(key: String)
}

/** Result of a single HTTP send. */
data class HttpResponse(
    val statusCode: Int,
    /** Already envelope-unwrapped body (the value of the top-level `data` field, or raw body). */
    val body: String?,
    val headers: Map<String, String> = emptyMap(),
) {
    fun header(name: String): String? {
        val lower = name.lowercase()
        return headers.entries.firstOrNull { it.key.lowercase() == lower }?.value
    }
}

/** Raised by the transport for a non-2xx HTTP response. */
class AttriaxHttpException(
    val statusCode: Int,
    val responseBody: String?,
    val headers: Map<String, String> = emptyMap(),
    message: String? = null,
) : Exception(message ?: "HTTP $statusCode")

/** Raised by the transport for a timeout (retryable). */
class AttriaxTimeoutException(message: String? = null, cause: Throwable? = null) :
    Exception(message ?: "request timed out", cause)

/** Raised by the transport for a transport/IO failure (retryable). */
class AttriaxTransportException(message: String? = null, cause: Throwable? = null) :
    Exception(message ?: "transport failure", cause)

/**
 * HTTP transport port. The single long-lived platform implementation stamps the
 * mandatory User-Agent on every request and unwraps the `{data:.}`
 * envelope. `throws` on non-2xx / timeout / transport failure using the typed
 * exceptions above so the retry policy can classify them.
 */
interface HttpClient {
    /**
     * POST a JSON [body] to [path] (appended to the configured base URL).
     * @return the successful (2xx) response, envelope-unwrapped.
     * @throws AttriaxHttpException on non-2xx.
     * @throws AttriaxTimeoutException on timeout.
     * @throws AttriaxTransportException on any other transport/IO failure.
     */
    fun post(path: String, body: String): HttpResponse

    /**
     * GET [path] (appended to the configured base URL). Same headers, timeout,
     * envelope-unwrapping, and exception classification as [post]. Used for the few
     * read-only config pulls (e.g. the SKAN conversion-value config).
     *
     * A default is provided so the pure engine's test fakes need not implement it
     * unless they exercise a GET path; every real platform transport overrides it.
     * @return the successful (2xx) response, envelope-unwrapped.
     * @throws AttriaxHttpException on non-2xx.
     * @throws AttriaxTimeoutException on timeout.
     * @throws AttriaxTransportException on any other transport/IO failure.
     */
    fun get(path: String): HttpResponse =
        throw AttriaxTransportException("GET is not supported by this transport")
}

/** Connectivity port. Implementations invoke [Listener.onConnectivityRestored] on regain. */
interface ConnectivityMonitor {
    fun interface Listener {
        fun onConnectivityRestored()
    }

    fun isConnected(): Boolean
    fun register(listener: Listener)
    fun unregister(listener: Listener)
}

/**
 * A cancellable, repeating scheduler port (heartbeat timers).
 *
 * The pure session-lifecycle manager schedules the heartbeat through this seam so
 * timers stay deterministic in tests (a fake scheduler can fire ticks on
 * demand with no wall-clock sleep). The Android implementation runs off the main
 * thread on a `ScheduledExecutorService`; a scheduled task must never leak, so
 * [ScheduledHandle.cancel] cancels the underlying future.
 */
interface AttriaxScheduler {
    /** A handle to a scheduled repeating task; [cancel] stops future ticks. */
    fun interface ScheduledHandle {
        fun cancel()
    }

    /** Run [action] every [intervalMs] (first tick after one interval). */
    fun schedulePeriodic(intervalMs: Long, action: () -> Unit): ScheduledHandle

    /**
     * Run [action] ONCE after [delayMs] (a coalesced deferred flush).
     * Mirrors the Flutter synchronizer's one-shot `Timer` used by
     * `_scheduleDeferredFlush`: the engine arms a single pending flush after the
     * configured `eventFlushInterval` and re-arms it after it fires; [cancel] on the
     * returned handle drops the pending tick. Off the main thread like
     * [schedulePeriodic].
     */
    fun scheduleOnce(delayMs: Long, action: () -> Unit): ScheduledHandle
}

/**
 * Binds app foreground/background detection to the session lifecycle
 * The engine calls [bind] once its session-lifecycle manager is ready and
 * [unbind] on reset/dispose. The Android implementation registers a
 * `ProcessLifecycleOwner` observer; the pure engine + its tests use a no-op or
 * fake binder and drive the lifecycle manager directly.
 */
interface AttriaxLifecycleBinder {
    fun bind()
    fun unbind()

    /** A no-op binder for tests / hosts that drive lifecycle transitions manually. */
    object Noop : AttriaxLifecycleBinder {
        override fun bind() = Unit
        override fun unbind() = Unit
    }
}

/**
 * Supplies the raw native device-id candidates used by [AttriaxDeviceIdentityResolver].
 * The Android implementation reads Settings.Secure ANDROID_ID and (when
 * permitted) the Advertising ID; the Apple implementation reads
 * `identifierForVendor` (IDFV) and (when ATT-authorized) the IDFA; every candidate
 * may be null/unavailable.
 */
interface DeviceIdSources {
    /** Settings.Secure ANDROID_ID (SSAID), or null when unavailable / not Android. */
    fun androidSsaid(): String?

    /**
     * Advertising ID — Android GAID or Apple ATT-authorized IDFA — or null when
     * unavailable / not collected. The [AttriaxDeviceIdentityResolver] stamps the
     * per-platform source slug (`android_gaid` / `ios_idfa`) via its
     * `advertisingIdSource`.
     */
    fun advertisingId(): String?

    /**
     * Apple `identifierForVendor` (IDFV), or null off-Apple / when unavailable.
     * Defaulted to null so the Android + desktop sources are unchanged; the Apple
     * source (`AttriaxAppleDeviceIdSources`) overrides it. Preferred over the
     * advertising id in resolution (iOS branch: IDFV → IDFA → persistent).
     */
    fun iosIdfv(): String? = null
}
