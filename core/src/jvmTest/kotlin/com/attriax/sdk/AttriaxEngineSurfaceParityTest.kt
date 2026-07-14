package com.attriax.sdk

import com.attriax.sdk.internal.contract.AttriaxDispatchContract
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reflect-the-engine guard, facade side (the counterpart to the desktop-native
 * `AttriaxCApiDispatchContractTest`).
 *
 * Snapshots the PUBLIC method surface of every engine facade via JVM reflection and
 * asserts that each method is EITHER wired to a C-ABI dispatch key (in
 * [AttriaxDispatchContract.METHODS]) OR explicitly listed as intentionally not
 * dispatched. Adding / renaming / removing a public facade action changes the
 * reflected snapshot and FAILS this test, forcing a conscious decision:
 *  - wire it into `AttriaxCApi.route()` + [AttriaxDispatchContract.METHODS], or
 *  - record it below as intentionally-not-dispatched (with the reason).
 *
 * The dispatch keys that do NOT originate on a facade (lifecycle + engine getters +
 * `submitAsaToken` / `validateReceipt`, which live on [Attriax] itself) are pinned as
 * the [engineLevelMethods] remainder, so the two sides account for the full contract.
 */
class AttriaxEngineSurfaceParityTest {

    /**
     * A facade class plus how each of its public methods relates to the C-ABI:
     * [dispatched] maps a facade method name → its dispatch key; [notDispatched] lists
     * the public methods intentionally NOT reachable through the uniform dispatch.
     */
    private class FacadeSpec(
        val type: Class<*>,
        val dispatched: Map<String, String>,
        val notDispatched: Set<String>,
    )

    private val facades = listOf(
        FacadeSpec(
            AttriaxTracking::class.java,
            dispatched = mapOf(
                "getEnabled" to "getEnabled",
                "setEnabled" to "setEnabled",
                "getAnonymousTrackingEnabled" to "getAnonymousTracking",
                "setAnonymousTrackingEnabled" to "setAnonymousTracking",
                "recordEvent" to "recordEvent",
                "recordPageView" to "recordPageView",
                "recordPurchase" to "recordPurchase",
                "recordRefund" to "recordRefund",
                "recordAdRevenue" to "recordAdRevenue",
                "recordAdEvent" to "recordAdEvent",
                "recordError" to "recordError",
                "recordNotification" to "recordNotification",
                "setUser" to "setUser",
                "setUserProperty" to "setUserProperty",
                "setUserProperties" to "setUserProperties",
                "clearUserProperties" to "clearUserProperties",
                "registerFirebaseMessagingToken" to "registerFirebaseMessagingToken",
                "registerApplePushToken" to "registerApplePushToken",
            ),
            // Convenience wrappers over the single parametric recordNotification.
            notDispatched = setOf(
                "recordNotificationReceived",
                "recordNotificationOpened",
                "recordNotificationDismissed",
            ),
        ),
        FacadeSpec(
            AttriaxGdprConsent::class.java,
            dispatched = mapOf(
                "getState" to "getGdprConsentState",
                "getValues" to "getGdprConsentValues",
                "isWaitingForConsent" to "getIsWaitingForGdprConsent",
                "needsConsent" to "needsGdprConsent",
                "setConsent" to "setGdprConsent",
                "setNotRequired" to "setGdprConsentNotRequired",
                "reset" to "resetGdprConsent",
                "requestDataErasure" to "requestGdprDataErasure",
            ),
            notDispatched = emptySet(),
        ),
        FacadeSpec(
            AttriaxAttConsent::class.java,
            dispatched = mapOf(
                "getStatus" to "getAttStatus",
                "setStatus" to "setAttStatus",
                "requestAuthorization" to "requestAttAuthorization",
            ),
            notDispatched = emptySet(),
        ),
        FacadeSpec(
            AttriaxCcpaConsent::class.java,
            dispatched = mapOf(
                "getDoNotSell" to "getDoNotSell",
                "getUsPrivacy" to "getUsPrivacy",
                "setDoNotSell" to "setDoNotSell",
                "setUsPrivacy" to "setUsPrivacy",
                "set" to "setCcpaConsent",
            ),
            notDispatched = emptySet(),
        ),
        FacadeSpec(
            AttriaxDeepLinks::class.java,
            dispatched = mapOf(
                "handleUri" to "handleIncomingLink",
                "getLatestDeepLink" to "getLatestDeepLink",
                "getInitialDeepLink" to "getInitialDeepLink",
                "getRawInitialDeepLink" to "getRawInitialDeepLink",
                "getInitialDeepLinkResolved" to "getInitialDeepLinkResolved",
                "recordDeepLink" to "recordDeepLink",
            ),
            // Listeners flow through the C-ABI event callback; the blocking waits and
            // createDynamicLink are not exposed through the uniform dispatch today.
            notDispatched = setOf(
                "completeInitialLinkIfAbsent",
                "addListener",
                "removeListener",
                "addRawListener",
                "removeRawListener",
                "waitForInitialDeepLink",
                "waitResolution",
                "createDynamicLink",
            ),
        ),
        FacadeSpec(
            AttriaxSynchronization::class.java,
            dispatched = mapOf(
                "isSynchronized" to "getIsSynchronized",
                "getState" to "getSynchronizationState",
            ),
            // State listeners flow through the C-ABI event callback.
            notDispatched = setOf("addStateListener", "removeStateListener"),
        ),
        FacadeSpec(
            AttriaxReferrer::class.java,
            dispatched = mapOf(
                "getOriginalInstallReferrer" to "getOriginalInstallReferrer",
                "getReinstallReferrer" to "getReinstallReferrer",
                "getRawInstallReferrer" to "getRawInstallReferrer",
                "getSessionReferrer" to "getSessionReferrer",
                "getLatestDeepLinkReferrer" to "getLatestDeepLinkReferrer",
            ),
            notDispatched = emptySet(),
        ),
        FacadeSpec(
            AttriaxSkan::class.java,
            dispatched = mapOf(
                "getState" to "getSkanState",
                "updateConversionValue" to "updateSkanConversionValue",
            ),
            // Blocking backend config pull; not exposed through the uniform dispatch.
            notDispatched = setOf("fetchConversionConfig"),
        ),
    )

    /** Dispatch keys that live on the [Attriax] engine itself, not on a facade. */
    private val engineLevelMethods = setOf(
        "init", "reset", "dispose", "flush",
        "getDeviceId", "getIsFirstLaunch", "getIsInitialized", "getSdkSnapshot",
        "submitAsaToken", "validateReceipt",
    )

    private fun publicMethodNames(type: Class<*>): Set<String> =
        type.declaredMethods
            .filter {
                Modifier.isPublic(it.modifiers) &&
                    !it.isSynthetic &&
                    !it.isBridge &&
                    !it.name.contains('$')
            }
            .map { it.name }
            .toSet()

    @Test
    fun facadePublicSurfaceIsFullyAccountedFor() {
        for (facade in facades) {
            val reflected = publicMethodNames(facade.type)
            val expected = facade.dispatched.keys + facade.notDispatched
            assertEquals(
                expected,
                reflected,
                "Public surface of ${facade.type.simpleName} changed. Wire any new " +
                    "method into AttriaxCApi.route() + AttriaxDispatchContract.METHODS, " +
                    "or record it as intentionally-not-dispatched in this test.",
            )
        }
    }

    @Test
    fun everyDispatchedFacadeMethodMapsToaContractKey() {
        for (facade in facades) {
            for ((facadeMethod, dispatchKey) in facade.dispatched) {
                assertTrue(
                    dispatchKey in AttriaxDispatchContract.METHODS,
                    "${facade.type.simpleName}.$facadeMethod maps to dispatch key " +
                        "'$dispatchKey', which is absent from AttriaxDispatchContract.METHODS.",
                )
            }
        }
    }

    @Test
    fun contractSplitsCleanlyIntoFacadeAndEngineLevelKeys() {
        val facadeDispatchKeys = facades.flatMap { it.dispatched.values }.toSet()
        // No dispatch key is claimed by two facades.
        assertEquals(
            facades.sumOf { it.dispatched.size },
            facadeDispatchKeys.size,
            "A dispatch key is mapped from more than one facade method.",
        )
        // The facade keys plus the engine-level keys account for the WHOLE contract,
        // with nothing left over on either side.
        assertEquals(
            AttriaxDispatchContract.METHODS,
            facadeDispatchKeys + engineLevelMethods,
            "The facade-dispatched keys + engine-level keys must equal the contract exactly.",
        )
        assertTrue(
            facadeDispatchKeys.intersect(engineLevelMethods).isEmpty(),
            "A dispatch key is listed as both facade-level and engine-level.",
        )
    }
}
