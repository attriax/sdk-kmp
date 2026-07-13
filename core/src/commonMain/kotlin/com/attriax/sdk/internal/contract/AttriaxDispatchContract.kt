package com.attriax.sdk.internal.contract

/**
 * The canonical, centrally-pinned set of method names dispatchable through the
 * C-ABI JSON bridge — i.e. every `when (method)` case of `route()` in
 * `nativeMain/.../AttriaxCApi.kt`, which is what the desktop / WebGL wrappers
 * (Unity P/Invoke, Flutter FFI) drive via `attriax_dispatch`.
 *
 * This is the SINGLE source of truth for the dispatch surface, and it lives in the
 * KMP core so the wrappers mirror the core rather than the core chasing them. Two
 * guards keep it from silently drifting from the actual engine surface:
 *
 *  - `AttriaxCApiDispatchContractTest` (desktop-native) drives the REAL exported
 *    `attriax_dispatch` for every name here and asserts none routes to
 *    `unimplemented:<method>` — so a case removed from `route()` (or a name added
 *    here without wiring it) FAILS; and
 *  - `AttriaxEngineSurfaceParityTest` (JVM) snapshots the public engine facades, so
 *    adding / renaming a public action without revisiting this list + `route()`
 *    FAILS.
 *
 * These are dispatch KEYS (the C-ABI method vocabulary), NOT wire field names — the
 * request/response body shapes are pinned separately by the golden fixtures in
 * `AttriaxWireContractGoldenTest`.
 */
internal object AttriaxDispatchContract {

    /**
     * Every dispatchable method name, grouped exactly as the `route()` sections are.
     * Order is not load-bearing (dispatch is keyed by name), but the grouping is kept
     * aligned with `route()` so the two read side by side.
     */
    val METHODS: Set<String> = linkedSetOf(
        // -------- lifecycle --------
        "init", "reset", "dispose", "flush",

        // -------- engine state getters / setters --------
        "getDeviceId", "getIsFirstLaunch", "getIsInitialized", "getIsSynchronized",
        "getSynchronizationState", "getSdkSnapshot", "getEnabled", "setEnabled",
        "getAnonymousTracking", "setAnonymousTracking",

        // -------- tracking / revenue --------
        "recordEvent", "recordPageView", "recordPurchase", "recordRefund",
        "recordAdRevenue", "recordAdEvent", "recordError",

        // -------- notifications --------
        "recordNotification",

        // -------- identify / user --------
        "setUser", "setUserProperty", "setUserProperties", "clearUserProperties",
        "registerFirebaseMessagingToken", "registerApplePushToken",

        // -------- GDPR consent --------
        "setGdprConsent", "setGdprConsentNotRequired", "resetGdprConsent",
        "getGdprConsentState", "getGdprConsentValues", "getIsWaitingForGdprConsent",
        "needsGdprConsent", "requestGdprDataErasure",

        // -------- Apple ATT --------
        "getAttStatus", "setAttStatus", "requestAttAuthorization",

        // -------- CCPA --------
        "getDoNotSell", "setDoNotSell", "getUsPrivacy", "setUsPrivacy",

        // -------- deep links --------
        "handleIncomingLink", "getLatestDeepLink", "getInitialDeepLink",
        "getRawInitialDeepLink", "getInitialDeepLinkResolved", "recordDeepLink",

        // -------- referrer --------
        "getOriginalInstallReferrer", "getReinstallReferrer", "getRawInstallReferrer",
        "getLatestDeepLinkReferrer", "getSessionReferrer",

        // -------- SKAdNetwork --------
        "getSkanState", "updateSkanConversionValue",

        // -------- Apple Search Ads --------
        "submitAsaToken",

        // -------- receipt validation --------
        "validateReceipt",
    )
}
