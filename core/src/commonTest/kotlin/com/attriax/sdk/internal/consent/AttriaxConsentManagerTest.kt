package com.attriax.sdk.internal.consent

import com.attriax.sdk.AttriaxConfig
import com.attriax.sdk.internal.AttriaxClock
import com.attriax.sdk.internal.AttriaxExecutor
import com.attriax.sdk.internal.HttpClient
import com.attriax.sdk.internal.HttpResponse
import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the consent state machine + generation-guarded sync
 * (PARITY §5, rows C1/C2/C3). The centerpiece is [downgrade race guard discards
 * a stale echo and re-syncs the newer state] — the deterministic reproduction of
 * the anonymous-analytics incident.
 *
 * The Android test used a real single-thread `Executor` + `CountDownLatch`es to
 * land the newer decision mid-upsert. On KMP the same race is reproduced
 * DETERMINISTICALLY, single-threaded: the sync runs inline via a synchronous
 * [AttriaxExecutor] fake, and the transport re-enters `setConsent(false)` from
 * inside the first (older) upsert — landing the newer decision before the (now
 * stale) echo is applied, exactly as the concurrent version did. All assertions
 * are preserved.
 */
class AttriaxConsentManagerTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    /** Runs submitted tasks inline (synchronous) for the non-race tests. */
    private class InlineExecutor : AttriaxExecutor {
        override fun execute(command: () -> Unit) = command()
    }

    /** Echoes back exactly what was upserted (state + values). */
    private class EchoTransport : AttriaxConsentTransport {
        val upserts = ArrayList<Map<String, Any?>>()
        override fun checkGdprConsent(projectToken: String, consentId: String) =
            AttriaxRemoteConsentStatus(
                AttriaxGdprConsentState.UNKNOWN, null, needsConsent = true,
                countryCode = null, regionSource = null, checkedAtIso = null,
            )

        override fun upsertGdprConsent(
            projectToken: String,
            consentId: String,
            state: AttriaxGdprConsentState,
            values: AttriaxGdprConsentValues?,
            countryCode: String?,
            regionSource: String?,
            clientOccurredAtIso: String?,
        ): AttriaxRemoteConsentStatus {
            upserts.add(
                linkedMapOf(
                    "projectToken" to projectToken,
                    "consentId" to consentId,
                    "state" to state,
                    "values" to values,
                ),
            )
            return AttriaxRemoteConsentStatus(
                state, values, needsConsent = false,
                countryCode = countryCode, regionSource = regionSource,
                checkedAtIso = clientOccurredAtIso,
            )
        }

        override fun eraseGdprData(projectToken: String, deviceId: String) {}
    }

    private fun config(anonymous: Boolean = true) = AttriaxConfig(
        projectToken = "tok",
        gdprEnabled = true,
        anonymousTracking = anonymous,
    )

    private fun manager(
        store: MapStore = MapStore(),
        transport: AttriaxConsentTransport = EchoTransport(),
        executor: AttriaxExecutor = InlineExecutor(),
        clockMs: Long = 1_000L,
    ) = AttriaxConsentManager(
        config = config(),
        clock = AttriaxClock { clockMs },
        consentStore = AttriaxConsentStore(store),
        transport = transport,
        syncExecutor = executor,
    )

    // -------- rows C1: state transitions + needsConsent --------

    @Test
    fun `default state is unknown and waiting`() {
        val m = manager()
        assertEquals(AttriaxGdprConsentState.UNKNOWN, m.gdprConsentState)
        assertTrue(m.isWaitingForGdprConsent)
        assertNull(m.gdprConsentValues)
    }

    @Test
    fun `setConsent applies granted state and values locally and immediately`() {
        val m = manager()
        m.setConsent(analytics = true, attribution = false, adEvents = true)
        assertEquals(AttriaxGdprConsentState.GRANTED, m.gdprConsentState)
        assertFalse(m.isWaitingForGdprConsent)
        assertEquals(
            AttriaxGdprConsentValues(analytics = true, attribution = false, adEvents = true),
            m.gdprConsentValues,
        )
    }

    @Test
    fun `setNotRequired resolves to notRequired and clears values`() {
        val m = manager()
        m.setNotRequired()
        assertEquals(AttriaxGdprConsentState.NOT_REQUIRED, m.gdprConsentState)
        assertFalse(m.isWaitingForGdprConsent)
        assertNull(m.gdprConsentValues)
    }

    @Test
    fun `reset returns to unknown pending evaluation`() {
        val m = manager()
        m.setConsent(analytics = true, attribution = true, adEvents = true)
        m.reset()
        assertEquals(AttriaxGdprConsentState.UNKNOWN, m.gdprConsentState)
        assertTrue(m.isWaitingForGdprConsent)
    }

    @Test
    fun `needsConsent localOnly returns waiting without calling transport`() {
        val transport = EchoTransport()
        val m = manager(transport = transport)
        assertTrue(m.needsConsent(localOnly = true))
        assertTrue(transport.upserts.isEmpty())
    }

    @Test
    fun `needsConsent returns false after consent granted`() {
        val m = manager()
        m.setConsent(analytics = true, attribution = true, adEvents = true)
        assertFalse(m.needsConsent(localOnly = true))
    }

    // -------- row C2: wire shape — consentId present, NO deviceId --------

    @Test
    fun `consent upsert body carries consentId and no device or user id`() {
        val store = MapStore()
        val http = RecordingHttp()
        val m = AttriaxConsentManager(
            config = config(),
            clock = AttriaxClock { 1_000L },
            consentStore = AttriaxConsentStore(store),
            transport = AttriaxHttpConsentTransport(http),
            syncExecutor = InlineExecutor(),
        )
        m.setConsent(analytics = true, attribution = false, adEvents = false)

        val (path, body) = http.posts.single { it.first == "/api/sdk/v1/consent/gdpr" }
        val decoded = Json.decodeObject(body)
        assertEquals("/api/sdk/v1/consent/gdpr", path)
        assertEquals("tok", decoded["projectToken"])
        assertTrue((decoded["consentId"] as String).isNotBlank())
        // Critically: NO device / user identity in the consent body.
        assertFalse(decoded.containsKey("deviceId"))
        assertFalse(decoded.containsKey("deviceIdSource"))
        assertFalse(decoded.containsKey("externalUserId"))
        // state uses the api snake_case token.
        assertEquals("granted", decoded["state"])
        @Suppress("UNCHECKED_CAST")
        val values = decoded["values"] as Map<String, Any?>
        assertEquals(true, values["analytics"])
        assertEquals(false, values["attribution"])
        assertEquals(false, values["adEvents"])
    }

    @Test
    fun `setNotRequired upsert body uses the not_required snake_case token`() {
        val http = RecordingHttp()
        val m = AttriaxConsentManager(
            config = config(),
            clock = AttriaxClock { 1_000L },
            consentStore = AttriaxConsentStore(MapStore()),
            transport = AttriaxHttpConsentTransport(http),
            syncExecutor = InlineExecutor(),
        )
        m.setNotRequired()
        val (_, body) = http.posts.single { it.first == "/api/sdk/v1/consent/gdpr" }
        assertEquals("not_required", Json.decodeObject(body)["state"])
    }

    @Test
    fun `needsConsent remote check sends consentId and no device id and applies the echo`() {
        val http = RecordingCheckHttp(
            """{"state":"not_required","needsConsent":false,"checkedAt":"2026-07-06T00:00:00.000Z"}""",
        )
        val m = AttriaxConsentManager(
            config = config(),
            clock = AttriaxClock { 1_000L },
            consentStore = AttriaxConsentStore(MapStore()),
            transport = AttriaxHttpConsentTransport(http),
            syncExecutor = InlineExecutor(),
        )
        val waiting = m.needsConsent(localOnly = false)

        val (path, body) = http.posts.single()
        val decoded = Json.decodeObject(body)
        assertEquals("/api/sdk/v1/consent/gdpr/check", path)
        assertEquals("tok", decoded["projectToken"])
        assertTrue((decoded["consentId"] as String).isNotBlank())
        assertFalse(decoded.containsKey("deviceId"))
        // The echo (not_required) was applied, so we are no longer waiting.
        assertFalse(waiting)
        assertEquals(AttriaxGdprConsentState.NOT_REQUIRED, m.gdprConsentState)
    }

    /** Records the check POST and returns a fixed status body. */
    private class RecordingCheckHttp(private val statusBody: String) : HttpClient {
        val posts = ArrayList<Pair<String, String>>()
        override fun post(path: String, body: String): HttpResponse {
            posts.add(path to body)
            return HttpResponse(200, statusBody)
        }
    }

    // -------- persistence --------

    @Test
    fun `consent decision is persisted and restored`() {
        val store = MapStore()
        manager(store = store).setConsent(analytics = true, attribution = false, adEvents = true)

        val restored = manager(store = store)
        restored.restore()
        assertEquals(AttriaxGdprConsentState.GRANTED, restored.gdprConsentState)
        assertEquals(
            AttriaxGdprConsentValues(analytics = true, attribution = false, adEvents = true),
            restored.gdprConsentValues,
        )
    }

    // ==================================================================== //
    // row C3 — THE DOWNGRADE RACE. This reproduces the anonymous-analytics
    // incident: a rapid setConsent(true) then setConsent(false) where the OLDER
    // (true) upsert echo returns AFTER the newer (false) state is set. The
    // generation guard must discard the stale true-echo and re-sync false.
    // ==================================================================== //

    @Test
    fun `downgrade race guard discards a stale echo and re-syncs the newer state`() {
        val store = MapStore()

        var callCount = 0
        val echoedAnalytics = ArrayList<Boolean?>()
        lateinit var m: AttriaxConsentManager

        // The transport re-enters the manager on the FIRST (older) upsert, landing
        // the newer setConsent(false) BEFORE this (now stale) echo is applied — the
        // deterministic analogue of the concurrent mid-flight decision.
        val transport = object : AttriaxConsentTransport {
            override fun checkGdprConsent(projectToken: String, consentId: String) =
                error("not used")

            override fun upsertGdprConsent(
                projectToken: String,
                consentId: String,
                state: AttriaxGdprConsentState,
                values: AttriaxGdprConsentValues?,
                countryCode: String?,
                regionSource: String?,
                clientOccurredAtIso: String?,
            ): AttriaxRemoteConsentStatus {
                val n = ++callCount
                echoedAnalytics.add(values?.analytics)
                if (n == 1) {
                    // The NEWER decision lands while the older upsert is "in flight".
                    m.setConsent(analytics = false, attribution = false, adEvents = false)
                }
                // Echo back the snapshot that was upserted (stale for call #1).
                return AttriaxRemoteConsentStatus(
                    state, values, needsConsent = false, countryCode = null,
                    regionSource = null, checkedAtIso = clientOccurredAtIso,
                )
            }

            override fun eraseGdprData(projectToken: String, deviceId: String) {}
        }

        m = AttriaxConsentManager(
            config = config(),
            clock = AttriaxClock { 1_000L },
            consentStore = AttriaxConsentStore(store),
            transport = transport,
            syncExecutor = InlineExecutor(),
        )

        // setConsent(true): starts the sync loop; its (first) upsert lands the newer
        // decision, so the stale echo is discarded and the loop re-syncs (false).
        m.setConsent(analytics = true, attribution = true, adEvents = true)

        // Two upserts must have happened (the stale first + the re-sync).
        assertEquals(2, callCount)
        // First upsert carried the (true) snapshot; the re-sync carried (false).
        assertEquals(true, echoedAnalytics[0])
        assertEquals(false, echoedAnalytics[1])

        // FINAL local state is FALSE — the stale true-echo was DISCARDED, never
        // clobbering the newer setConsent(false).
        assertEquals(AttriaxGdprConsentState.GRANTED, m.gdprConsentState)
        assertFalse(m.gdprConsentValues!!.analytics)
        assertFalse(m.gdprConsentValues!!.attribution)
        assertFalse(m.gdprConsentValues!!.adEvents)
    }

    /** HttpClient recorder for the wire-body assertions. */
    private class RecordingHttp : HttpClient {
        val posts = ArrayList<Pair<String, String>>()
        override fun post(path: String, body: String): HttpResponse {
            posts.add(path to body)
            // Echo a granted status so the manager's applyRemoteStatus is exercised.
            return HttpResponse(
                200,
                """{"state":"granted","needsConsent":false,"checkedAt":"2026-07-06T00:00:00.000Z"}""",
            )
        }
    }
}
