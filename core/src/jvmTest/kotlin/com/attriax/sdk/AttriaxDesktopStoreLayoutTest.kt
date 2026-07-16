package com.attriax.sdk

import com.attriax.sdk.internal.AttriaxDeviceIdentityStore
import com.attriax.sdk.internal.consent.AttriaxConsentStore
import com.attriax.sdk.internal.queue.AttriaxQueueManager
import com.attriax.sdk.internal.session.AttriaxSessionSnapshotStore
import com.attriax.sdk.jvm.AttriaxFileKeyValueStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The #78 desktop two-file store layout over a real dataDir: two engines with
 * DIFFERENT project tokens on the SAME default dataDir share the device id (#72)
 * but never each other's queue/consent/session, and a legacy single-file store
 * carries forward on upgrade.
 */
class AttriaxDesktopStoreLayoutTest {

    private lateinit var dataDir: File

    @BeforeTest
    fun setup() {
        dataDir = File.createTempFile("attriax-layout", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @AfterTest
    fun cleanup() {
        dataDir.deleteRecursively()
    }

    @Test
    fun differentProjectTokensIsolateMutableStateButShareTheDeviceId() {
        val storeA = AttriaxDesktop.createProjectScopedStore(dataDir, "tok_a", "com.app.a")
        storeA.putString(AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK, "dev-1")
        storeA.putString(AttriaxQueueManager.KEY_QUEUE, "queue-a")
        storeA.putString(AttriaxConsentStore.KEY_CONSENT, "consent-a")
        storeA.putString(AttriaxSessionSnapshotStore.KEY_SESSION, "session-a")

        val storeB = AttriaxDesktop.createProjectScopedStore(dataDir, "tok_b", "com.app.b")
        // The device id is machine-wide by design (#72).
        assertEquals("dev-1", storeB.getString(AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK))
        // B sees NONE of A's mutable state.
        assertNull(storeB.getString(AttriaxQueueManager.KEY_QUEUE))
        assertNull(storeB.getString(AttriaxConsentStore.KEY_CONSENT))
        assertNull(storeB.getString(AttriaxSessionSnapshotStore.KEY_SESSION))

        // B's writes never leak into A — even across fresh instances (real files).
        storeB.putString(AttriaxQueueManager.KEY_QUEUE, "queue-b")
        storeB.putString(AttriaxConsentStore.KEY_CONSENT, "consent-b")
        storeB.putString(AttriaxSessionSnapshotStore.KEY_SESSION, "session-b")

        val reopenedA = AttriaxDesktop.createProjectScopedStore(dataDir, "tok_a", "com.app.a")
        assertEquals("queue-a", reopenedA.getString(AttriaxQueueManager.KEY_QUEUE))
        assertEquals("consent-a", reopenedA.getString(AttriaxConsentStore.KEY_CONSENT))
        assertEquals("session-a", reopenedA.getString(AttriaxSessionSnapshotStore.KEY_SESSION))
        assertEquals("dev-1", reopenedA.getString(AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK))
    }

    @Test
    fun legacySingleFileStateCarriesForwardOnUpgrade() {
        // A pre-split install: everything in the one default-named file.
        val legacy = AttriaxFileKeyValueStore(dataDir)
        legacy.putString(AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK, "dev-legacy")
        legacy.putString(AttriaxConsentStore.KEY_CONSENT, """{"state":"granted"}""")
        legacy.putString(AttriaxConsentStore.KEY_CONSENT_ID, "legacy-consent-id")
        legacy.putString(AttriaxQueueManager.KEY_QUEUE, """[{"id":"q1"}]""")
        legacy.putString(
            AttriaxSessionSnapshotStore.KEY_SESSION,
            """{"sessionId":"s-1","appPackageName":"com.app.a"}""",
        )
        legacy.putString("attriax.first_launch_completed", "true")

        // First run after the SDK upgrade (the app that owns the session).
        val upgraded = AttriaxDesktop.createProjectScopedStore(dataDir, "tok_a", "com.app.a")
        assertEquals("dev-legacy", upgraded.getString(AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK))
        assertEquals("""{"state":"granted"}""", upgraded.getString(AttriaxConsentStore.KEY_CONSENT))
        assertEquals("""[{"id":"q1"}]""", upgraded.getString(AttriaxQueueManager.KEY_QUEUE))
        assertTrue(
            upgraded.getString(AttriaxSessionSnapshotStore.KEY_SESSION)!!.contains("s-1"),
            "the owning app keeps its session",
        )
        assertEquals("true", upgraded.getString("attriax.first_launch_completed"))
        // The legacy shared consentId is never re-shared: this project mints fresh.
        assertNull(upgraded.getString(AttriaxConsentStore.KEY_CONSENT_ID))

        // Copy-and-leave: the legacy file still serves older-SDK processes.
        val legacyReopened = AttriaxFileKeyValueStore(dataDir)
        assertEquals("""{"state":"granted"}""", legacyReopened.getString(AttriaxConsentStore.KEY_CONSENT))
        assertEquals("legacy-consent-id", legacyReopened.getString(AttriaxConsentStore.KEY_CONSENT_ID))

        // A DIFFERENT project upgrading on the same machine inherits the shared-era
        // consent decision (today's semantics one last time) but NOT the foreign
        // session snapshot and NOT the consentId.
        val other = AttriaxDesktop.createProjectScopedStore(dataDir, "tok_b", "com.app.b")
        assertEquals("""{"state":"granted"}""", other.getString(AttriaxConsentStore.KEY_CONSENT))
        assertNull(other.getString(AttriaxSessionSnapshotStore.KEY_SESSION))
        assertNull(other.getString(AttriaxConsentStore.KEY_CONSENT_ID))
    }

    @Test
    fun perProjectFilesLandNextToTheSharedFile() {
        AttriaxDesktop.createProjectScopedStore(dataDir, "tok_a", null)
            .putString(AttriaxQueueManager.KEY_QUEUE, "q")
        val names = dataDir.listFiles()!!.map { it.name }
        assertTrue(
            names.any { it.startsWith("attriax-sdk-p") && it.endsWith(".properties") },
            "per-project properties file exists: $names",
        )
    }
}
