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
import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.request.AttriaxEndpoints
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * driven through the REAL [Attriax] engine with a fake
 * challenge transport + fake provider (no device / Play Services). Proves the
 * app-open body carries the attestation envelope when a token is produced, omits
 * it in every degrade path, and that init is never broken.
 *
 * The challenge fetch + provider resolution run on the engine's (synchronous fake)
 * flush executor, so the app-open body is captured deterministically from the
 * transport's POST to `/open` (the wire truth) after `init()` returns.
 */
class AttriaxAttestationEngineTest {

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

    /**
     * Transport that answers the challenge endpoint with a configurable body and
     * records every (path, body) posted. The open body is captured here — the wire
     * truth — because a successful flush drains the open out of the queue.
     */
    private class ChallengeTransport(
        private val challengeResponder: () -> HttpResponse = {
            HttpResponse(200, """{"nonce":"server_nonce","expiresInSeconds":120}""")
        },
    ) : HttpClient {
        val posts = mutableListOf<Pair<String, String>>()
        override fun post(path: String, body: String): HttpResponse {
            posts.add(path to body)
            return if (path == AttriaxEndpoints.ATTESTATION_CHALLENGE) {
                challengeResponder.invoke()
            } else {
                HttpResponse(200, "{}")
            }
        }
        fun challengeCalls(): Int = posts.count { it.first == AttriaxEndpoints.ATTESTATION_CHALLENGE }
    }

    private class FixedSources(private val ssaid: String?) : DeviceIdSources {
        override fun androidSsaid(): String? = ssaid
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

    private fun newEngine(
        store: MapStore,
        transport: ChallengeTransport,
        attestationEnabled: Boolean,
        provider: AttriaxAttestationProvider?,
    ): Attriax {
        store.putString("attriax.first_launch_completed", "false")
        val resolver = AttriaxDeviceIdentityResolver(FixedSources("SSAID-123"), collectAdvertisingId = false)
        val identityStore = AttriaxDeviceIdentityStore(store, resolver)
        return Attriax(
            config = AttriaxConfig(
                projectToken = "tok",
                attestationEnabled = attestationEnabled,
                attestationProvider = provider,
            ),
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

    /**
     * The decoded body of the POST to `/open` (enqueued + flushed inline during
     * init on the synchronous fake executor). The open is dispatched once
     * attestation resolves, so the wire body is the deterministic source of truth.
     */
    @Suppress("UNCHECKED_CAST")
    private fun openBody(transport: ChallengeTransport): Map<String, Any?>? {
        val open = transport.posts.firstOrNull { it.first == AttriaxEndpoints.OPEN } ?: return null
        return Json.decode(open.second) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun attestation(body: Map<String, Any?>): Map<String, Any?>? =
        body["attestation"] as? Map<String, Any?>

    // -------- AT1: enabled + provider returns token → open carries the envelope --------

    @Test
    fun enabledWithTokenOpenCarriesEnvelope() {
        val store = MapStore()
        val transport = ChallengeTransport()
        newEngine(
            store, transport,
            attestationEnabled = true,
            provider = { nonce -> AttriaxAttestationToken(token = "integrity_token_for_$nonce") },
        )

        val body = openBody(transport)!!
        val envelope = attestation(body)!!
        assertEquals("play_integrity", envelope["provider"])
        assertEquals("integrity_token_for_server_nonce", envelope["token"])
        assertEquals("server_nonce", envelope["nonce"])
        assertFalse(envelope.containsKey("keyId"))
        assertEquals(1, transport.challengeCalls())
    }

    // -------- AT2: provider returns null → open sent with no attestation --------

    @Test
    fun enabledProviderNullOpenHasNoAttestation() {
        val store = MapStore()
        val transport = ChallengeTransport()
        newEngine(store, transport, attestationEnabled = true, provider = { null })

        val body = openBody(transport)!!
        assertNull(attestation(body))
        // Open still sent — challenge was attempted.
        assertEquals(1, transport.challengeCalls())
    }

    // -------- AT2: provider THROWS → open still sent, no attestation --------

    @Test
    fun enabledProviderThrowsOpenStillSentWithoutAttestation() {
        val store = MapStore()
        val transport = ChallengeTransport()
        newEngine(
            store, transport,
            attestationEnabled = true,
            provider = { throw IllegalStateException("play services down") },
        )

        val body = openBody(transport)!!
        assertNull(attestation(body))
        assertTrue(body.containsKey("projectToken")) // a real open was enqueued
    }

    // -------- AT2: challenge fails (5xx) → open still sent, no attestation --------

    @Test
    fun challengeFailureOpenStillSentWithoutAttestation() {
        val store = MapStore()
        val transport = ChallengeTransport(challengeResponder = { throw AttriaxHttpException(503, "down") })
        newEngine(
            store, transport,
            attestationEnabled = true,
            provider = { AttriaxAttestationToken(token = "should_not_be_used") },
        )

        val body = openBody(transport)!!
        assertNull(attestation(body))
    }

    // -------- AT2: challenge malformed → open still sent, no attestation --------

    @Test
    fun challengeMalformedOpenStillSentWithoutAttestation() {
        val store = MapStore()
        val transport = ChallengeTransport(challengeResponder = { HttpResponse(200, "not-json") })
        newEngine(
            store, transport,
            attestationEnabled = true,
            provider = { AttriaxAttestationToken(token = "t") },
        )

        val body = openBody(transport)!!
        assertNull(attestation(body))
    }

    // -------- config: disabled → challenge never called, no attestation --------

    @Test
    fun disabledChallengeNeverCalledOpenHasNoAttestation() {
        val store = MapStore()
        val transport = ChallengeTransport()
        newEngine(
            store, transport,
            attestationEnabled = false,
            provider = { AttriaxAttestationToken(token = "t") },
        )

        val body = openBody(transport)!!
        assertNull(attestation(body))
        assertEquals(0, transport.challengeCalls())
    }
}
