package com.attriax.sdk.internal

import com.attriax.sdk.internal.consent.AttriaxConsentStore
import com.attriax.sdk.internal.queue.AttriaxQueueManager
import com.attriax.sdk.internal.session.AttriaxSessionSnapshotStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The #78 desktop store split: device-identity keys route to the machine-shared
 * store, everything else to the per-project store, and the one-time legacy
 * single-file import carries state forward WITHOUT re-sharing the consentId or a
 * foreign app's session snapshot.
 */
class AttriaxProjectScopedKeyValueStoreTest {

    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
    }

    private val shared = MapStore()
    private val project = MapStore()
    private val store = AttriaxProjectScopedKeyValueStore(shared, project)

    @Test
    fun deviceIdentityKeysRouteToTheSharedStore() {
        for (key in AttriaxProjectScopedKeyValueStore.SHARED_IDENTITY_KEYS) {
            store.putString(key, "v-$key")
        }
        for (key in AttriaxProjectScopedKeyValueStore.SHARED_IDENTITY_KEYS) {
            assertEquals("v-$key", shared.map[key], "$key lands in the shared store")
            assertFalse(project.map.containsKey(key), "$key never touches the project store")
            assertEquals("v-$key", store.getString(key), "$key reads back through the composite")
        }
        store.remove(AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK)
        assertFalse(shared.map.containsKey(AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK))
    }

    @Test
    fun everyOtherKeyRoutesToTheProjectStore() {
        val keys = listOf(
            AttriaxQueueManager.KEY_QUEUE,
            AttriaxConsentStore.KEY_CONSENT,
            AttriaxConsentStore.KEY_CONSENT_ID,
            AttriaxSessionSnapshotStore.KEY_SESSION,
            "attriax.some_future_key",
        )
        for (key in keys) store.putString(key, "v-$key")
        for (key in keys) {
            assertEquals("v-$key", project.map[key], "$key lands in the project store")
            assertFalse(shared.map.containsKey(key), "$key never touches the shared store")
            assertEquals("v-$key", store.getString(key))
        }
    }

    @Test
    fun importCarriesLegacyStateForwardExceptIdentityAndConsentId() {
        shared.map[AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK] = "dev-1"
        shared.map[AttriaxConsentStore.KEY_CONSENT] = """{"state":"granted"}"""
        shared.map[AttriaxConsentStore.KEY_CONSENT_ID] = "legacy-consent-id"
        shared.map[AttriaxQueueManager.KEY_QUEUE] = "[]"
        shared.map["attriax.first_launch_completed"] = "true"

        store.importLegacySingleFileState(shared.map.keys.toSet(), appPackageName = "com.app.a")

        // Carried forward into the project store.
        assertEquals("""{"state":"granted"}""", project.map[AttriaxConsentStore.KEY_CONSENT])
        assertEquals("[]", project.map[AttriaxQueueManager.KEY_QUEUE])
        assertEquals("true", project.map["attriax.first_launch_completed"])
        // The cross-project correlator is NEVER imported — each project mints fresh.
        assertNull(project.map[AttriaxConsentStore.KEY_CONSENT_ID])
        assertNull(store.getString(AttriaxConsentStore.KEY_CONSENT_ID))
        // Identity keys stay shared, not copied.
        assertFalse(project.map.containsKey(AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK))
        assertEquals("dev-1", store.getString(AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK))
        // Copy-and-leave: the legacy file is untouched.
        assertEquals("""{"state":"granted"}""", shared.map[AttriaxConsentStore.KEY_CONSENT])
        assertEquals("legacy-consent-id", shared.map[AttriaxConsentStore.KEY_CONSENT_ID])
        // Marker set.
        assertEquals(
            AttriaxProjectScopedKeyValueStore.LEGACY_IMPORT_DONE,
            project.map[AttriaxProjectScopedKeyValueStore.KEY_LEGACY_IMPORT],
        )
    }

    @Test
    fun importKeepsTheOwnAppsSessionSnapshot() {
        shared.map[AttriaxSessionSnapshotStore.KEY_SESSION] =
            """{"sessionId":"s-1","appPackageName":"com.app.a"}"""
        store.importLegacySingleFileState(shared.map.keys.toSet(), appPackageName = "com.app.a")
        assertTrue(project.map.containsKey(AttriaxSessionSnapshotStore.KEY_SESSION))
    }

    @Test
    fun importSkipsAForeignAppsSessionSnapshot() {
        shared.map[AttriaxSessionSnapshotStore.KEY_SESSION] =
            """{"sessionId":"s-1","appPackageName":"com.app.a"}"""
        store.importLegacySingleFileState(shared.map.keys.toSet(), appPackageName = "com.app.b")
        // A foreign snapshot would become `replacedSession` on restore and emit a
        // recovered session END for another project's session id — never import it.
        assertFalse(project.map.containsKey(AttriaxSessionSnapshotStore.KEY_SESSION))
    }

    @Test
    fun importMatchesNullPackageNames() {
        // Single-app hosts often configure no package name on either side.
        shared.map[AttriaxSessionSnapshotStore.KEY_SESSION] = """{"sessionId":"s-1"}"""
        store.importLegacySingleFileState(shared.map.keys.toSet(), appPackageName = null)
        assertTrue(project.map.containsKey(AttriaxSessionSnapshotStore.KEY_SESSION))
    }

    @Test
    fun importSkipsACorruptSessionSnapshot() {
        shared.map[AttriaxSessionSnapshotStore.KEY_SESSION] = "not json"
        store.importLegacySingleFileState(shared.map.keys.toSet(), appPackageName = null)
        assertFalse(project.map.containsKey(AttriaxSessionSnapshotStore.KEY_SESSION))
    }

    @Test
    fun importRunsExactlyOncePerProjectStore() {
        shared.map[AttriaxConsentStore.KEY_CONSENT] = """{"state":"granted"}"""
        store.importLegacySingleFileState(shared.map.keys.toSet(), appPackageName = null)
        assertEquals("""{"state":"granted"}""", store.getString(AttriaxConsentStore.KEY_CONSENT))

        // The user clears consent; a later launch must NOT resurrect it from the
        // frozen legacy file.
        store.remove(AttriaxConsentStore.KEY_CONSENT)
        store.importLegacySingleFileState(shared.map.keys.toSet(), appPackageName = null)
        assertNull(store.getString(AttriaxConsentStore.KEY_CONSENT))
    }

    @Test
    fun importNeverOverwritesStateTheProjectAlreadyWrote() {
        shared.map[AttriaxQueueManager.KEY_QUEUE] = "legacy-queue"
        project.map[AttriaxQueueManager.KEY_QUEUE] = "own-queue"
        store.importLegacySingleFileState(shared.map.keys.toSet(), appPackageName = null)
        assertEquals("own-queue", project.map[AttriaxQueueManager.KEY_QUEUE])
    }

    @Test
    fun projectStoreFileSuffixIsStableTrimmedAndTokenSpecific() {
        val suffix = AttriaxProjectScopedKeyValueStore.projectStoreFileSuffix("tok_a")
        assertEquals(suffix, AttriaxProjectScopedKeyValueStore.projectStoreFileSuffix("tok_a"))
        assertEquals(suffix, AttriaxProjectScopedKeyValueStore.projectStoreFileSuffix("  tok_a  "))
        assertNotEquals(suffix, AttriaxProjectScopedKeyValueStore.projectStoreFileSuffix("tok_b"))
        assertEquals(16, suffix.length)
        assertTrue(suffix.all { it in "0123456789abcdef" }, "hex-only suffix: $suffix")
    }
}
