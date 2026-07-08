package com.attriax.sdk.internal.deeplink

import com.attriax.sdk.AttriaxDeepLinkEvent
import com.attriax.sdk.AttriaxDeepLinkTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deep-link manager tests (PARITY §6, rows DL1–DL4): 2s dedup window, deferred
 * fire-once + persisted flag, and waitForInitialDeepLink resolution.
 */
class AttriaxDeepLinkManagerTest {

    /** A resolve dispatcher that immediately resolves with a canned response. */
    private class RecordingDispatch(
        private val response: Map<String, Any?>? = MATCHED_RESPONSE,
    ) {
        val calls = ArrayList<Triple<String, Map<String, Any?>, Boolean>>()
        fun asLambda(): (AttriaxUri, Map<String, Any?>, String, Boolean, (Map<String, Any?>?) -> Unit) -> Unit =
            { uri, metadata, _, isInitial, onResolved ->
                calls.add(Triple(uri.toString(), metadata, isInitial))
                onResolved(response)
            }
    }

    private class FlagStore {
        var handled = false
    }

    private fun manager(
        clock: () -> Long,
        dispatch: RecordingDispatch,
        flags: FlagStore = FlagStore(),
        handleBrowserAction: (com.attriax.sdk.AttriaxBrowserAction?) -> Boolean = { false },
    ) = AttriaxDeepLinkManager(
        nowMs = clock,
        resolveDispatch = dispatch.asLambda(),
        readDeferredHandled = { flags.handled },
        writeDeferredHandled = { flags.handled = it },
        handleBrowserAction = handleBrowserAction,
    )

    @Test
    fun dedupesIdenticalUriWithinTwoSeconds() {
        var now = 0L
        val dispatch = RecordingDispatch()
        val mgr = manager({ now }, dispatch)

        mgr.handleIncomingLink("https://sub.attriax.com/p", isInitialLink = false)
        now = 1_500 // within 2s window
        mgr.handleIncomingLink("https://sub.attriax.com/p", isInitialLink = false)

        assertEquals(1, dispatch.calls.size)
    }

    @Test
    fun allowsIdenticalUriAfterTwoSeconds() {
        var now = 0L
        val dispatch = RecordingDispatch()
        val mgr = manager({ now }, dispatch)

        mgr.handleIncomingLink("https://sub.attriax.com/p", isInitialLink = false)
        now = 2_500 // beyond 2s window
        mgr.handleIncomingLink("https://sub.attriax.com/p", isInitialLink = false)

        assertEquals(2, dispatch.calls.size)
    }

    @Test
    fun doesNotDedupeDistinctUris() {
        val dispatch = RecordingDispatch()
        val mgr = manager({ 0L }, dispatch)

        mgr.handleIncomingLink("https://sub.attriax.com/a", isInitialLink = false)
        mgr.handleIncomingLink("https://sub.attriax.com/b", isInitialLink = false)

        assertEquals(2, dispatch.calls.size)
    }

    @Test
    fun emitsResolvedEventToListeners() {
        val dispatch = RecordingDispatch()
        val mgr = manager({ 42L }, dispatch)
        val received = ArrayList<AttriaxDeepLinkEvent>()
        mgr.addListener { received.add(it) }

        mgr.handleIncomingLink("https://sub.attriax.com/promo", isInitialLink = false)

        assertEquals(1, received.size)
        assertTrue(received.first().found)
        assertEquals(AttriaxDeepLinkTrigger.FOREGROUND, received.first().trigger)
    }

    @Test
    fun waitForInitialDeepLinkResolvesWithLaunchEvent() {
        val dispatch = RecordingDispatch()
        val mgr = manager({ 10L }, dispatch)

        assertFalse(mgr.isInitialDeepLinkResolved)
        mgr.handleIncomingLink("https://sub.attriax.com/launch", isInitialLink = true)

        assertTrue(mgr.isInitialDeepLinkResolved)
        val event = mgr.waitForInitialDeepLink()
        assertEquals("https://sub.attriax.com/launch", event?.uri?.toString())
        assertEquals(AttriaxDeepLinkTrigger.COLD_START, event?.trigger)
    }

    @Test
    fun completeInitialLinkIfAbsentResolvesToNull() {
        val dispatch = RecordingDispatch()
        val mgr = manager({ 0L }, dispatch)

        assertFalse(mgr.isInitialDeepLinkResolved)
        mgr.completeInitialLinkIfAbsent()

        assertTrue(mgr.isInitialDeepLinkResolved)
        assertNull(mgr.waitForInitialDeepLink())
    }

    @Test
    fun invalidInitialUriStillCompletesProbe() {
        val dispatch = RecordingDispatch()
        val mgr = manager({ 0L }, dispatch)

        mgr.handleIncomingLink("   ", isInitialLink = true)

        assertTrue(mgr.isInitialDeepLinkResolved)
        assertEquals(0, dispatch.calls.size)
    }

    @Test
    fun deferredDeepLinkFiresOnceAndPersistsHandledFlag() {
        val dispatch = RecordingDispatch()
        val flags = FlagStore()
        val mgr = manager({ 5L }, dispatch, flags)
        val received = ArrayList<AttriaxDeepLinkEvent>()
        mgr.addListener { received.add(it) }

        val openData = mapOf<String, Any?>(
            "deepLink" to mapOf("uri" to "https://sub.attriax.com/deferred"),
        )
        mgr.handleDeferredAppOpen(openData)
        mgr.handleDeferredAppOpen(openData) // second call must be a no-op

        assertEquals(1, received.size)
        assertEquals(AttriaxDeepLinkTrigger.DEFERRED, received.first().trigger)
        assertTrue(flags.handled)
    }

    @Test
    fun deferredDeepLinkSkippedWhenAlreadyHandledAcrossRestart() {
        val dispatch = RecordingDispatch()
        val flags = FlagStore().apply { handled = true } // persisted from a prior run
        val mgr = manager({ 5L }, dispatch, flags)
        val received = ArrayList<AttriaxDeepLinkEvent>()
        mgr.addListener { received.add(it) }

        mgr.handleDeferredAppOpen(mapOf("deepLink" to mapOf("uri" to "https://sub.attriax.com/x")))

        assertEquals(0, received.size)
    }

    @Test
    fun removedListenerNoLongerReceivesEvents() {
        val dispatch = RecordingDispatch()
        val mgr = manager({ 0L }, dispatch)
        val received = ArrayList<AttriaxDeepLinkEvent>()
        val listener = com.attriax.sdk.AttriaxDeepLinkListener { received.add(it) }
        mgr.addListener(listener)
        mgr.removeListener(listener)

        mgr.handleIncomingLink("https://sub.attriax.com/p", isInitialLink = false)

        assertEquals(0, received.size)
    }

    @Test
    fun recordDeepLinkReturnsResolvedEvent() {
        val dispatch = RecordingDispatch()
        val mgr = manager({ 7L }, dispatch)
        val received = ArrayList<AttriaxDeepLinkEvent>()
        mgr.addListener { received.add(it) }

        val event = mgr.recordDeepLink(
            rawUri = "https://sub.attriax.com/manual",
            metadata = mapOf("k" to "v"),
        )

        assertTrue(event != null)
        assertTrue(event.found)
        assertEquals(AttriaxDeepLinkTrigger.FOREGROUND, event.trigger)
        // Also emitted to observers.
        assertEquals(1, received.size)
        // The caller metadata reached the resolve dispatch.
        assertEquals("v", dispatch.calls.first().second["k"])
    }

    @Test
    fun recordDeepLinkReturnsNullOnFailedResolution() {
        val dispatch = RecordingDispatch(response = null) // resolve failed / withheld
        val mgr = manager({ 0L }, dispatch)
        assertNull(mgr.recordDeepLink(rawUri = "https://sub.attriax.com/x", metadata = null))
    }

    @Test
    fun waitResolutionReturnsResolutionForRawEvent() {
        val dispatch = RecordingDispatch()
        val mgr = manager({ 3L }, dispatch)
        val raws = ArrayList<com.attriax.sdk.AttriaxRawDeepLinkEvent>()
        mgr.addRawListener { raws.add(it) }

        mgr.handleIncomingLink("https://sub.attriax.com/promo", isInitialLink = false)

        // The raw event was published; its resolution already completed inline.
        val resolution = mgr.waitResolution(raws.first())
        assertTrue(resolution != null)
        assertTrue(resolution.found)
    }

    @Test
    fun waitResolutionReturnsNullForUnknownRawEvent() {
        val dispatch = RecordingDispatch()
        val mgr = manager({ 0L }, dispatch)
        val unknown = com.attriax.sdk.AttriaxRawDeepLinkEvent(
            uri = AttriaxUri.parse("https://sub.attriax.com/never")!!,
            receivedAtMs = 0L,
            isInitial = false,
        )
        assertNull(mgr.waitResolution(unknown))
    }

    @Test
    fun invokesBrowserOpenerWithResolvedBrowserActionUrl() {
        val dispatch = RecordingDispatch(response = BROWSER_ACTION_RESPONSE)
        val opened = ArrayList<String>()
        val mgr = manager({ 0L }, dispatch, handleBrowserAction = { action ->
            action?.url?.let { opened.add(it) }
            action != null
        })

        val event = mgr.recordDeepLink(rawUri = "https://sub.attriax.com/promo", metadata = null)

        assertEquals(listOf("https://fallback.example.com/open"), opened)
        assertTrue(event?.handledBySdk == true)
    }

    @Test
    fun doesNotInvokeBrowserOpenerWithoutBrowserAction() {
        val dispatch = RecordingDispatch() // MATCHED_RESPONSE has no browserAction
        var calledWithNonNull = false
        val mgr = manager({ 0L }, dispatch, handleBrowserAction = { action ->
            if (action != null) calledWithNonNull = true
            false
        })

        val event = mgr.recordDeepLink(rawUri = "https://sub.attriax.com/promo", metadata = null)

        assertFalse(calledWithNonNull)
        assertFalse(event?.handledBySdk ?: true)
    }

    companion object {
        private val MATCHED_RESPONSE = mapOf<String, Any?>(
            "matched" to true,
            "status" to "matched",
            "isFirstLaunch" to false,
            "deepLink" to mapOf("uri" to null),
        )

        private val BROWSER_ACTION_RESPONSE = mapOf<String, Any?>(
            "matched" to true,
            "status" to "matched",
            "isFirstLaunch" to false,
            "deepLink" to mapOf("uri" to "https://sub.attriax.com/promo"),
            "browserAction" to mapOf(
                "url" to "https://fallback.example.com/open",
                "openMode" to "external",
            ),
        )
    }
}
