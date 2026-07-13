package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.ConnectivityMonitor
import com.attriax.sdk.internal.DeviceIdSources
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKAdNetwork surface driven through the REAL [Attriax] engine with fakes.
 * Proves the passthrough contract: the facade validates + applies the monotonic rules
 * and only reaches the on-device seam with the resolved fine/coarse/lock when the value
 * advances; the coarse-value enum maps correctly; off-iOS / disabled / invalid short-
 * circuit without a bridge call.
 */
class AttriaxSkanTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    private class NoopConnectivity : ConnectivityMonitor {
        override fun isConnected(): Boolean = false
        override fun register(listener: ConnectivityMonitor.Listener) {}
        override fun unregister(listener: ConnectivityMonitor.Listener) {}
    }

    private class RecordingTransport : HttpClient {
        override fun post(path: String, body: String): HttpResponse = HttpResponse(200, "{}")
    }

    private class FixedSources(private val ssaid: String?) : DeviceIdSources {
        override fun androidSsaid(): String? = ssaid
        override fun advertisingId(): String? = null
    }

    /** Records every on-device SKAN update the engine forwards to the seam. */
    private class RecordingSkanBridge(
        private val result: (Int, AttriaxSkanCoarseValue, Boolean) -> AttriaxSkanUpdateResult = { f, c, l ->
            AttriaxSkanUpdateResult(AttriaxSkanUpdateStatus.UPDATED, fineValue = f, coarseValue = c, lockWindow = l)
        },
    ) {
        data class Call(val fineValue: Int, val coarseValue: AttriaxSkanCoarseValue, val lockWindow: Boolean)
        val calls = mutableListOf<Call>()
        fun update(fineValue: Int, coarseValue: AttriaxSkanCoarseValue, lockWindow: Boolean): AttriaxSkanUpdateResult {
            calls.add(Call(fineValue, coarseValue, lockWindow))
            return result(fineValue, coarseValue, lockWindow)
        }
    }

    private val context = AttriaxContextSnapshot(
        packageName = "com.x",
        appVersion = "1.0.0",
        appBuildNumber = "1",
        deviceModel = "Pixel",
        deviceManufacturer = "Google",
        osVersion = "14",
        deviceTimezone = "UTC",
        deviceLocale = "en-US",
    )

    private fun newEngine(
        config: AttriaxConfig = AttriaxConfig(projectToken = "tok"),
        skanSupported: Boolean = true,
        bridge: RecordingSkanBridge = RecordingSkanBridge(),
    ): Attriax {
        val store = MapStore()
        store.putString("attriax.first_launch_completed", "false")
        val resolver = AttriaxDeviceIdentityResolver(FixedSources("SSAID-123"), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = config,
            store = store,
            transport = RecordingTransport(),
            connectivity = NoopConnectivity(),
            context = context,
            deviceIdentityStore = identityStore,
            clock = AttriaxClock { 1_000L },
            flushExecutor = AttriaxTestBackgroundExecutor(),
            consentExecutor = AttriaxTestBackgroundExecutor(),
            skanSupportedSeam = { skanSupported },
            updatePostbackConversionValueSeam = { f, c, l -> bridge.update(f, c, l) },
        ).also { it.init() }
    }

    // -------- happy path: facade → seam with resolved args --------

    @Test
    fun updateConversionValueForwardsResolvedArgsToSeam() {
        val bridge = RecordingSkanBridge()
        val engine = newEngine(bridge = bridge)
        val result = engine.skan.updateConversionValue(fineValue = 12, lockWindow = true)

        assertEquals(AttriaxSkanUpdateStatus.UPDATED, result.status)
        assertEquals(1, bridge.calls.size)
        // 12 < 20 → derived coarse LOW; lockWindow forwarded.
        assertEquals(RecordingSkanBridge.Call(12, AttriaxSkanCoarseValue.LOW, true), bridge.calls.single())
        assertEquals(12, result.fineValue)
        assertEquals(AttriaxSkanCoarseValue.LOW, result.coarseValue)
        assertEquals(true, result.lockWindow)
    }

    @Test
    fun coarseValueIsDerivedFromFineValueThresholds() {
        for ((fine, coarse) in listOf(
            0 to AttriaxSkanCoarseValue.LOW,
            19 to AttriaxSkanCoarseValue.LOW,
            20 to AttriaxSkanCoarseValue.MEDIUM,
            39 to AttriaxSkanCoarseValue.MEDIUM,
            40 to AttriaxSkanCoarseValue.HIGH,
            63 to AttriaxSkanCoarseValue.HIGH,
        )) {
            val bridge = RecordingSkanBridge()
            val engine = newEngine(bridge = bridge)
            engine.skan.updateConversionValue(fineValue = fine)
            assertEquals(coarse, bridge.calls.single().coarseValue, "derived coarse mismatch for fine=$fine")
        }
    }

    @Test
    fun explicitCoarseValueIsMaxedAgainstDerived() {
        val bridge = RecordingSkanBridge()
        val engine = newEngine(bridge = bridge)
        // fine=5 derives LOW, but caller forces HIGH → HIGH wins (max).
        engine.skan.updateConversionValue(fineValue = 5, coarseValue = AttriaxSkanCoarseValue.HIGH)
        assertEquals(AttriaxSkanCoarseValue.HIGH, bridge.calls.single().coarseValue)
    }

    @Test
    fun stateReflectsLastAppliedUpdate() {
        val engine = newEngine()
        assertNull(engine.skan.state?.fineValue)
        engine.skan.updateConversionValue(fineValue = 30)
        val state = engine.skan.state
        assertEquals(true, state?.enabled)
        assertEquals(30, state?.fineValue)
        assertEquals(AttriaxSkanCoarseValue.MEDIUM, state?.coarseValue)
    }

    // -------- monotonic gating --------

    @Test
    fun lowerFineValueDoesNotAdvanceAndSkipsSeam() {
        val bridge = RecordingSkanBridge()
        val engine = newEngine(bridge = bridge)
        engine.skan.updateConversionValue(fineValue = 30)
        val result = engine.skan.updateConversionValue(fineValue = 10)
        assertEquals(AttriaxSkanUpdateStatus.ALREADY_AT_OR_ABOVE_VALUE, result.status)
        // Only the first (advancing) update reached the seam.
        assertEquals(1, bridge.calls.size)
        // Stored fine value stayed at the monotonic max.
        assertEquals(30, engine.skan.state?.fineValue)
    }

    // -------- short-circuits (no bridge call) --------

    @Test
    fun unsupportedPlatformReturnsNotSupportedAndNullState() {
        val bridge = RecordingSkanBridge()
        val engine = newEngine(skanSupported = false, bridge = bridge)
        val result = engine.skan.updateConversionValue(fineValue = 30)
        assertEquals(AttriaxSkanUpdateStatus.NOT_SUPPORTED, result.status)
        assertTrue(bridge.calls.isEmpty())
        assertNull(engine.skan.state)
    }

    @Test
    fun disabledConfigReturnsDisabledAndSkipsSeam() {
        val bridge = RecordingSkanBridge()
        val engine = newEngine(
            config = AttriaxConfig(projectToken = "tok", skan = AttriaxSkanConfig(enabled = false)),
            bridge = bridge,
        )
        val result = engine.skan.updateConversionValue(fineValue = 30)
        assertEquals(AttriaxSkanUpdateStatus.DISABLED, result.status)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test
    fun invalidFineValueReturnsInvalidValueAndSkipsSeam() {
        for (invalid in listOf(-1, 64, 100)) {
            val bridge = RecordingSkanBridge()
            val engine = newEngine(bridge = bridge)
            val result = engine.skan.updateConversionValue(fineValue = invalid)
            assertEquals(AttriaxSkanUpdateStatus.INVALID_VALUE, result.status, "expected invalid for $invalid")
            assertTrue(bridge.calls.isEmpty())
        }
    }

    @Test
    fun coarseValueWireValuesMatchFlutter() {
        assertEquals("low", AttriaxSkanCoarseValue.LOW.wireValue)
        assertEquals("medium", AttriaxSkanCoarseValue.MEDIUM.wireValue)
        assertEquals("high", AttriaxSkanCoarseValue.HIGH.wireValue)
    }
}
