package com.attriax.sdk.internal.session

import com.attriax.sdk.internal.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Session snapshot persist / restore / revalidate + corruption. */
class AttriaxSessionSnapshotStoreTest {

    private class MapStore : KeyValueStore {
        val data = HashMap<String, String>()
        override fun getString(key: String): String? = data[key]
        override fun putString(key: String, value: String?) {
            if (value == null) data.remove(key) else data[key] = value
        }
        override fun remove(key: String) { data.remove(key) }
    }

    private fun snapshot() = AttriaxSessionSnapshot(
        sessionId = "s1",
        startedAtMs = 1_000L,
        lastActivityAtMs = 2_000L,
        heartbeatIntervalMs = 300_000L,
        deviceId = "d1",
        platform = "android",
        appPackageName = "com.example",
        appVersion = "1.2.3",
        appBuildNumber = "42",
        locale = "en-US",
        isFirstLaunch = true,
        sdkPackageVersion = "0.5.0",
    )

    @Test
    fun persistsAndRestoresSnapshotRoundTrip() {
        val store = MapStore()
        val subject = AttriaxSessionSnapshotStore(store)
        val snap = snapshot()

        subject.write(snap)
        assertEquals(snap, subject.read())
    }

    @Test
    fun writingNullClearsPersistedSnapshot() {
        val store = MapStore()
        val subject = AttriaxSessionSnapshotStore(store)
        subject.write(snapshot())

        subject.write(null)

        assertNull(subject.read())
        assertNull(store.getString(AttriaxSessionSnapshotStore.KEY_SESSION))
    }

    @Test
    fun readReturnsNullWhenAbsent() {
        assertNull(AttriaxSessionSnapshotStore(MapStore()).read())
    }

    @Test
    fun corruptPayloadIsClearedAndTreatedAsNoSession() {
        val store = MapStore()
        store.putString(AttriaxSessionSnapshotStore.KEY_SESSION, "{not valid json")

        val subject = AttriaxSessionSnapshotStore(store)
        assertNull(subject.read())
        // The corrupt payload is dropped so it never resurfaces.
        assertNull(store.getString(AttriaxSessionSnapshotStore.KEY_SESSION))
    }

    @Test
    fun snapshotMissingRequiredFieldIsTreatedAsCorrupt() {
        val store = MapStore()
        // Valid JSON object, but missing the required `sessionId` → decode throws → null.
        store.putString(AttriaxSessionSnapshotStore.KEY_SESSION, "{\"platform\":\"android\"}")

        assertNull(AttriaxSessionSnapshotStore(store).read())
    }
}
