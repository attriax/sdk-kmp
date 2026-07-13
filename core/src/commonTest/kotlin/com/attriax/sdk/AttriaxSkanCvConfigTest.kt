package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxContextSnapshot
import com.attriax.sdk.internal.AttriaxDeviceIdentityResolver
import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.AttriaxHttpException
import com.attriax.sdk.internal.ConnectivityMonitor
import com.attriax.sdk.internal.DeviceIdSources
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKAN conversion-value config pull (`skan.fetchConversionConfig`) driven through the
 * REAL [Attriax] engine with fakes. Proves the GET path/token, the decode of the api
 * `SdkCvConfigResponse` wire shape, and the best-effort null on 404 / transport
 * failure / malformed payload.
 */
class AttriaxSkanCvConfigTest {

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

    /** Records GET paths and replays a scripted response (a body, or a thrown error). */
    private class ScriptedTransport(
        private val getResult: () -> HttpResponse,
    ) : HttpClient {
        val getPaths = mutableListOf<String>()
        override fun post(path: String, body: String): HttpResponse = HttpResponse(200, "{}")
        override fun get(path: String): HttpResponse {
            getPaths.add(path)
            return getResult()
        }
    }

    private class FixedSources : DeviceIdSources {
        override fun androidSsaid(): String? = "SSAID-123"
        override fun advertisingId(): String? = null
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

    private fun newEngine(transport: ScriptedTransport): Attriax {
        val store = MapStore()
        store.putString("attriax.first_launch_completed", "false")
        val resolver = AttriaxDeviceIdentityResolver(FixedSources(), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = AttriaxConfig(projectToken = "tok"),
            store = store,
            transport = transport,
            connectivity = NoopConnectivity(),
            context = context,
            deviceIdentityStore = identityStore,
            clock = AttriaxClock { 1_000L },
            flushExecutor = AttriaxTestBackgroundExecutor(),
            consentExecutor = AttriaxTestBackgroundExecutor(),
        ).also { it.init() }
    }

    private val sampleBody = """
        {
          "schemaVersion": 3,
          "schemaUpdatedAt": "2026-07-01T00:00:00.000Z",
          "enabled": true,
          "disclaimer": "Reflects the configured schema.",
          "rules": [
            {
              "id": "grp-a:2",
              "groupId": "grp-a",
              "groupDisplayName": "Purchases",
              "startBit": 0,
              "bitCount": 3,
              "rank": 2,
              "bitContribution": 2,
              "whenEvent": "purchase",
              "whenConditions": [
                { "paramKey": "tier", "operator": "eq", "value": "gold" },
                { "paramKey": "__revenue", "operator": "gte", "value": 9.99 }
              ],
              "whenRevenue": { "operator": "gte", "value": 9.99 },
              "coarseValue": "high",
              "lockWindow": true
            }
          ]
        }
    """.trimIndent()

    @Test
    fun fetchDecodesConfigAndUsesTokenPath() {
        val transport = ScriptedTransport { HttpResponse(200, sampleBody) }
        val engine = newEngine(transport)

        val config = AttriaxSkan(engine).fetchConversionConfig()

        assertEquals(
            "${AttriaxEndpoints.SKAN_CV_CONFIG}/tok",
            transport.getPaths.single(),
        )
        assertTrue(config != null)
        assertEquals(3, config.schemaVersion)
        assertEquals(true, config.enabled)
        assertEquals("2026-07-01T00:00:00.000Z", config.schemaUpdatedAt)
        val rule = config.rules.single()
        assertEquals("grp-a:2", rule.id)
        assertEquals("grp-a", rule.groupId)
        assertEquals(0, rule.startBit)
        assertEquals(2, rule.bitContribution)
        assertEquals("purchase", rule.whenEvent)
        assertEquals(AttriaxSkanCoarseValue.HIGH, rule.coarseValue)
        assertEquals(true, rule.lockWindow)
        assertEquals(2, rule.whenConditions.size)
        assertEquals(
            AttriaxSkanCvValue.StringValue("gold"),
            rule.whenConditions.first().value,
        )
        assertEquals(
            AttriaxSkanCvValue.NumberValue(9.99),
            rule.whenRevenue?.value,
        )
    }

    @Test
    fun fetchReturnsNullOnNotFound() {
        val transport = ScriptedTransport {
            throw AttriaxHttpException(statusCode = 404, responseBody = "not found")
        }
        val engine = newEngine(transport)
        assertNull(AttriaxSkan(engine).fetchConversionConfig())
    }

    @Test
    fun fetchReturnsNullOnMalformedPayload() {
        val transport = ScriptedTransport { HttpResponse(200, "not-json") }
        val engine = newEngine(transport)
        assertNull(AttriaxSkan(engine).fetchConversionConfig())
    }
}
