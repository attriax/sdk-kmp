@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.attriax.sdk

import com.attriax.sdk.internal.json.Json
import kotlin.concurrent.Volatile
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString

/*
 * ============================================================================
 *  Attriax KMP core — C-ABI shared-library boundary (G1).
 * ============================================================================
 *
 *  A UNIFORM JSON-dispatch bridge over the [Attriax] engine, mirroring the JSON
 *  wrappers the Flutter platform interface and Unity `IAttriaxEnginePlatform`
 *  already speak. Desktop wrappers (Flutter FFI, Unity P/Invoke) load the produced
 *  `attriax_core.dll` / `libattriax_core.so` and drive the engine through five
 *  exported C functions (generated prototypes land in `libattriax_core_api.h`):
 *
 *    void*  attriax_create(const char* configJson, const char* dataDir);
 *    char*  attriax_dispatch(void* handle, const char* method, const char* argsJson);
 *    void   attriax_register_event_callback(void* handle,
 *                                           void (*callback)(const char* eventJson, void* userData),
 *                                           void* userData);
 *    void   attriax_free_string(char* ptr);
 *    void   attriax_destroy(void* handle);
 *
 *  MEMORY / OWNERSHIP:
 *   - The opaque `void* handle` is a [StableRef] to an [AttriaxNativeHandle] that
 *     carries the engine plus the registered event callback. Kotlin objects NEVER
 *     cross the boundary directly.
 *   - Every `char*` returned by `attriax_dispatch` is heap-allocated (nativeHeap)
 *     and MUST be released by the caller via `attriax_free_string`. Passing a
 *     pointer not produced here to `attriax_free_string` is undefined behavior.
 *   - `configJson`/`method`/`argsJson`/`eventJson` are NUL-terminated UTF-8. Like an
 *     `attriax_dispatch` result, `eventJson` is heap-allocated (nativeHeap) and its
 *     OWNERSHIP transfers to the C callback: the callback MUST release it via
 *     `attriax_free_string` when done. Delivery may be asynchronous across a thread
 *     boundary (e.g. a Dart `NativeCallable.listener` trampoline), so the wrapper
 *     does NOT free it — freeing after the call returns would be a use-after-free.
 *
 *  THREADING:
 *   - `attriax_dispatch` is synchronous (the native transport is `runBlocking`-
 *     bridged) and re-entrant across handles.
 *   - The registered C callback may be invoked on an ENGINE BACKGROUND THREAD (the
 *     flush executor for synchronization-state transitions, the resolution path for
 *     deep-link events). Marshaling back to a UI/main thread is the WRAPPER's
 *     responsibility.
 *
 *  SAFETY:
 *   - A null/garbage handle yields an `{"ok":false,...}` envelope (dispatch) or a
 *     no-op (destroy/register) rather than a crash, best-effort.
 *   - Every dispatch is wrapped so a Kotlin exception is converted to
 *     `{"ok":false,"error":"…"}` and NEVER unwinds across the C boundary.
 */

// ---------------------------------------------------------------------------
//  Per-handle state
// ---------------------------------------------------------------------------

/**
 * Everything one opaque handle owns: the [engine] and the (optional) registered C
 * event callback + its `userData`. StableRef-wrapped; resolved back on every
 * dispatch/destroy call.
 */
private class AttriaxNativeHandle(val engine: Attriax) {
    @Volatile
    var callback: CPointer<CFunction<(CPointer<ByteVar>?, COpaquePointer?) -> Unit>>? = null

    @Volatile
    var userData: COpaquePointer? = null

    @Volatile
    var alive: Boolean = true

    /**
     * Marshal [json] to a heap-allocated C string and invoke the registered
     * callback, transferring OWNERSHIP of the string to it (the callback releases it
     * via `attriax_free_string`). Ownership transfer — rather than free-after-return
     * — is required because the callback may deliver the event asynchronously across
     * a thread boundary (e.g. a Dart `NativeCallable.listener` trampoline), so the
     * bytes must outlive this call; freeing here would be a use-after-free. No
     * callback / dead handle → a silent no-op with nothing allocated. Never throws
     * into the engine.
     */
    fun emit(json: String) {
        if (!alive) return
        val cb = callback ?: return
        val ptr = json.toCReturnString()
        try {
            cb.invoke(ptr, userData)
        } catch (e: Throwable) {
            // A misbehaving C callback must never crash a background engine thread.
            // The callback never took ownership on throw, so reclaim the string here.
            nativeHeap.free(ptr)
        }
    }
}

// ---------------------------------------------------------------------------
//  Exported C-ABI functions
// ---------------------------------------------------------------------------

/**
 * Build an engine from [configJson] (see [buildConfig]) persisting under [dataDir]
 * (empty/null → the platform default via [attriaxNativeDefaultDataDir]) and return an
 * opaque handle, or `null` when construction fails (malformed config maps to defaults
 * and does not fail; a genuine engine-construction error yields null). The engine
 * itself is built by the per-platform [attriaxNativeCreateEngine] seam (desktop →
 * Ktor/file-store; apple → NSURLSession/NSUserDefaults). Engine-side listeners are
 * attached here so callbacks route through the handle even before one is registered.
 */
@CName("attriax_create")
fun attriaxCreate(configJson: CPointer<ByteVar>?, dataDir: CPointer<ByteVar>?): COpaquePointer? {
    return try {
        val config = buildConfig(decodeObjectOrEmpty(configJson?.toKString()))
        val dir = dataDir?.toKString()?.takeIf { it.isNotBlank() }
            ?: attriaxNativeDefaultDataDir()
        val engine = attriaxNativeCreateEngine(config, dir)
        val handle = AttriaxNativeHandle(engine)
        // Route the two engine event streams through the handle callback (which may
        // be null until attriax_register_event_callback is called → the emit no-ops).
        engine.synchronization.addStateListener { state -> handle.emit(syncStateEventJson(state)) }
        engine.deepLinks.addListener { event -> handle.emit(deepLinkEventJson(event)) }
        StableRef.create(handle).asCPointer()
    } catch (e: Throwable) {
        null
    }
}

/**
 * Route [method] (with decoded [argsJson]) to the engine and return a heap-allocated
 * result-JSON envelope (`{"ok":true,"value":…}` / `{"ok":false,"error":"…"}`). The
 * caller frees it via [attriaxFreeString]. Guards a null handle, bad args JSON, and
 * any thrown exception into an error envelope — never crashes the boundary.
 */
@CName("attriax_dispatch")
fun attriaxDispatch(
    handle: COpaquePointer?,
    method: CPointer<ByteVar>?,
    argsJson: CPointer<ByteVar>?,
): CPointer<ByteVar>? {
    val envelope = try {
        val h = handle.resolveHandle()
            ?: return errEnvelope("invalid_handle").toCReturnString()
        val methodName = method?.toKString()
            ?: return errEnvelope("missing_method").toCReturnString()
        val args = try {
            decodeObjectOrEmpty(argsJson?.toKString())
        } catch (e: Throwable) {
            return errEnvelope("bad_args_json: ${e.message}").toCReturnString()
        }
        route(h, methodName, args)
    } catch (e: Throwable) {
        errEnvelope("${e::class.simpleName ?: "error"}: ${e.message}")
    }
    return envelope.toCReturnString()
}

/**
 * Register (or clear, with a null [callback]) the C event callback for [handle]. The
 * callback receives NUL-terminated UTF-8 `eventJson`; it may be invoked on an engine
 * background thread. No-op on an invalid handle.
 */
@CName("attriax_register_event_callback")
fun attriaxRegisterEventCallback(
    handle: COpaquePointer?,
    callback: CPointer<CFunction<(CPointer<ByteVar>?, COpaquePointer?) -> Unit>>?,
    userData: COpaquePointer?,
) {
    val h = handle.resolveHandle() ?: return
    h.callback = callback
    h.userData = userData
}

/**
 * Free a string previously returned by [attriaxDispatch]. Null-safe. The pointer
 * MUST have originated from this library (nativeHeap allocation).
 */
@CName("attriax_free_string")
fun attriaxFreeString(ptr: CPointer<ByteVar>?) {
    if (ptr != null) nativeHeap.free(ptr)
}

/**
 * Dispose the engine behind [handle] and release the [StableRef]. Idempotent and
 * null/garbage-safe. After this call the handle is invalid.
 */
@CName("attriax_destroy")
fun attriaxDestroy(handle: COpaquePointer?) {
    if (handle == null) return
    try {
        val ref = handle.asStableRef<AttriaxNativeHandle>()
        val h = ref.get()
        h.alive = false
        h.callback = null
        h.userData = null
        try {
            h.engine.dispose()
        } catch (e: Throwable) {
            // Best-effort dispose; still release the StableRef below.
        }
        ref.dispose()
    } catch (e: Throwable) {
        // Garbage/already-freed handle — no-op.
    }
}

// ---------------------------------------------------------------------------
//  Dispatch routing
// ---------------------------------------------------------------------------

/**
 * Thin adapter over the canonical [AttriaxDispatcher.execute] command table: forward
 * the decoded [args] and JSON-encode the canonical result into the SAME `{"ok":…}`
 * envelope this boundary has always produced. All method routing + result-shaping now
 * lives in `commonMain` so every binding shares one dispatch table; this function only
 * bridges the C-ABI JSON envelope semantics (`Ok` → `okEnvelope`, `Err`/`Unimplemented`
 * → `errEnvelope`, with the `unimplemented:` prefix preserved).
 */
private fun route(handle: AttriaxNativeHandle, method: String, args: Map<String, Any?>): String =
    when (val result = AttriaxDispatcher.execute(handle.engine, method, args)) {
        is AttriaxDispatchResult.Ok -> okEnvelope(result.value)
        is AttriaxDispatchResult.Err -> errEnvelope(result.message)
        is AttriaxDispatchResult.Unimplemented -> errEnvelope("unimplemented:${result.method}")
    }

// ---------------------------------------------------------------------------
//  Config construction from JSON
// ---------------------------------------------------------------------------

/**
 * Build an [AttriaxConfig] from the decoded config map. Every unknown/missing field
 * falls back to the [AttriaxConfig] default. The object-seam `attestationProvider`
 * is intentionally NOT constructable from JSON (it stays null → no attestation).
 */
private fun buildConfig(j: Map<String, Any?>): AttriaxConfig = AttriaxConfig(
    projectToken = j.stringOr("projectToken", ""),
    apiBaseUrl = j.string("apiBaseUrl") ?: AttriaxConfig.DEFAULT_API_BASE_URL,
    appVersion = j.string("appVersion"),
    appBuildNumber = j.string("appBuildNumber"),
    appPackageName = j.string("appPackageName"),
    sdkMetadata = j.mapOrNull("sdkMetadata"),
    deviceContext = buildDeviceContext(j.mapOrNull("deviceContext")),
    enableDebugLogs = j.boolOr("enableDebugLogs", false),
    requestTimeoutMs = j.longOr("requestTimeoutMs", 12_000L),
    maxQueueSize = j.intOr("maxQueueSize", 500).let { if (it > 0) it else 500 },
    eventFlushIntervalMs = j.longOr("eventFlushIntervalMs", 60_000L),
    flushEventsImmediatelyOnFirstLaunch = j.boolOr("flushEventsImmediatelyOnFirstLaunch", true),
    collectAdvertisingId = j.boolOr("collectAdvertisingId", true),
    automaticCrashReportingEnabled = j.boolOr("automaticCrashReportingEnabled", true),
    gdprEnabled = j.boolOr("gdprEnabled", false),
    anonymousTracking = j.boolOr("anonymousTracking", true),
    sessionTrackingEnabled = j.boolOr("sessionTrackingEnabled", true),
    sessionHeartbeatIntervalMs = j.longOr("sessionHeartbeatIntervalMs", 5 * 60_000L),
    firstLaunchSessionHeartbeatIntervalMs = j.longOr("firstLaunchSessionHeartbeatIntervalMs", 30_000L),
    installReferrerEnabled = j.boolOr("installReferrerEnabled", true),
    attestationEnabled = j.boolOr("attestationEnabled", false),
    pinnedCertificateSha256Fingerprints = j.stringListOrNull("pinnedCertificateSha256Fingerprints") ?: emptyList(),
    automaticBrowserHandling = j.boolOr("automaticBrowserHandling", true),
    attStatus = parseAttStatus(j.string("attStatus")),
    requestTrackingAuthorizationOnInit = j.boolOr("requestTrackingAuthorizationOnInit", false),
    trackingAuthorizationStatusTimeoutMs = j.longOr("trackingAuthorizationStatusTimeoutMs", 60_000L),
    skan = j.mapOrNull("skan")?.let { s ->
        AttriaxSkanConfig(
            enabled = s.boolOr("enabled", true),
            registerFirstLaunchValue = s.boolOr("registerFirstLaunchValue", true),
        )
    },
    asaTokenCaptureEnabled = j.boolOr("asaTokenCaptureEnabled", true),
    doNotSell = j.boolOrNull("doNotSell"),
    usPrivacy = j.string("usPrivacy"),
)

/**
 * Build the optional wrapper-supplied [AttriaxDeviceContext]. The five model/
 * manufacturer/osVersion/timezone/language fields are REQUIRED by the type; when the
 * block is present but missing any of them we return null (no partial context)
 * rather than throwing.
 */
private fun buildDeviceContext(d: Map<String, Any?>?): AttriaxDeviceContext? {
    if (d == null) return null
    val model = d.string("model") ?: return null
    val manufacturer = d.string("manufacturer") ?: return null
    val osVersion = d.string("osVersion") ?: return null
    val timezone = d.string("timezone") ?: return null
    val language = d.string("language") ?: return null
    return AttriaxDeviceContext(
        model = model,
        manufacturer = manufacturer,
        osVersion = osVersion,
        timezone = timezone,
        language = language,
        brand = d.string("brand"),
        hardware = d.string("hardware"),
        name = d.string("name"),
        isPhysicalDevice = d.boolOrNull("isPhysicalDevice"),
        screenWidth = d.intOrNull("screenWidth"),
        screenHeight = d.intOrNull("screenHeight"),
        screenResolution = d.string("screenResolution"),
        devicePixelRatio = d.doubleOrNull("devicePixelRatio"),
        colorDepth = d.intOrNull("colorDepth"),
        supportedAbis = d.stringListOrNull("supportedAbis"),
        metadata = d.mapOrNull("metadata"),
        advertisingId = d.string("advertisingId"),
        androidId = d.string("androidId"),
    )
}

// ---------------------------------------------------------------------------
//  Event stream JSON (for the C callback)
// ---------------------------------------------------------------------------
//
//  The result-shaping maps + enum wire mappings + arg accessors these once sat beside
//  now live in commonMain (`AttriaxDispatcher.kt`), shared with every binding.
//  `deepLinkEventMap` and `AttriaxSynchronizationState.wire()` are `internal` there so
//  the two event-JSON encoders below still reach them.

private fun syncStateEventJson(state: AttriaxSynchronizationState): String =
    Json.encode(mapOf("type" to "synchronizationState", "state" to state.wire()))

private fun deepLinkEventJson(event: AttriaxDeepLinkEvent): String =
    Json.encode(mapOf("type" to "deepLink", "event" to deepLinkEventMap(event)))

// ---------------------------------------------------------------------------
//  JSON / C-string helpers
// ---------------------------------------------------------------------------

private fun okEnvelope(value: Any?): String = Json.encode(mapOf("ok" to true, "value" to value))

private fun errEnvelope(message: String): String = Json.encode(mapOf("ok" to false, "error" to message))

private fun decodeObjectOrEmpty(text: String?): Map<String, Any?> {
    if (text.isNullOrBlank()) return emptyMap()
    @Suppress("UNCHECKED_CAST")
    return (Json.decode(text) as? Map<String, Any?>) ?: emptyMap()
}

/** Resolve an opaque handle back to its [AttriaxNativeHandle], or null on garbage/null. */
private fun COpaquePointer?.resolveHandle(): AttriaxNativeHandle? {
    if (this == null) return null
    return try {
        this.asStableRef<AttriaxNativeHandle>().get()
    } catch (e: Throwable) {
        null
    }
}

/**
 * Copy this string into a fresh NUL-terminated UTF-8 buffer on the native heap. The
 * caller (or [emit]) owns it and frees it via [attriaxFreeString] / `nativeHeap.free`.
 */
private fun String.toCReturnString(): CPointer<ByteVar> {
    val bytes = this.encodeToByteArray()
    val ptr = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
    for (i in bytes.indices) ptr[i] = bytes[i]
    ptr[bytes.size] = 0
    return ptr
}
