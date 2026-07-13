package com.attriax.sdk.internal.contract

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the canonical dispatch-method vocabulary. This is the value-level anchor the
 * two drift guards reference:
 *  - the desktop-native `AttriaxCApiDispatchContractTest` proves every name here is
 *    routed by the real C-ABI `route()`, and
 *  - the JVM `AttriaxEngineSurfaceParityTest` proves the public engine surface stays
 *    in sync with it.
 *
 * If you add / rename / remove a C-ABI method, update BOTH `route()` and
 * [AttriaxDispatchContract.METHODS], and this expected snapshot alongside them.
 */
class AttriaxDispatchContractTest {

    private val expected = setOf(
        "init", "reset", "dispose", "flush",
        "getDeviceId", "getIsFirstLaunch", "getIsInitialized", "getIsSynchronized",
        "getSynchronizationState", "getSdkSnapshot", "getEnabled", "setEnabled",
        "getAnonymousTracking", "setAnonymousTracking",
        "recordEvent", "recordPageView", "recordPurchase", "recordRefund",
        "recordAdRevenue", "recordAdEvent", "recordError",
        "recordNotification",
        "setUser", "setUserProperty", "setUserProperties", "clearUserProperties",
        "registerFirebaseMessagingToken", "registerApplePushToken",
        "setGdprConsent", "setGdprConsentNotRequired", "resetGdprConsent",
        "getGdprConsentState", "getGdprConsentValues", "getIsWaitingForGdprConsent",
        "needsGdprConsent", "requestGdprDataErasure",
        "getAttStatus", "setAttStatus", "requestAttAuthorization",
        "getDoNotSell", "setDoNotSell", "getUsPrivacy", "setUsPrivacy",
        "handleIncomingLink", "getLatestDeepLink", "getInitialDeepLink",
        "getRawInitialDeepLink", "getInitialDeepLinkResolved", "recordDeepLink",
        "getOriginalInstallReferrer", "getReinstallReferrer", "getRawInstallReferrer",
        "getLatestDeepLinkReferrer", "getSessionReferrer",
        "getSkanState", "updateSkanConversionValue",
        "submitAsaToken",
        "validateReceipt",
    )

    @Test
    fun methodSetIsPinned() {
        assertEquals(expected, AttriaxDispatchContract.METHODS)
    }

    @Test
    fun methodSetHasNoDuplicatesOrBlanks() {
        // A `Set` already dedups; assert the declared literal count matches so a
        // duplicate literal in the source is caught rather than silently collapsed.
        assertEquals(58, AttriaxDispatchContract.METHODS.size)
        assertEquals(0, AttriaxDispatchContract.METHODS.count { it.isBlank() })
    }
}
