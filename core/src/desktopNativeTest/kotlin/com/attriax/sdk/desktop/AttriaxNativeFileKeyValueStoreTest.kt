package com.attriax.sdk.desktop

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies the native POSIX file [AttriaxNativeFileKeyValueStore]: put/get/remove
 * roundtrip and restart durability (a fresh instance on the same dir sees prior
 * writes), matching the JVM store's contract.
 */
class AttriaxNativeFileKeyValueStoreTest {

    private lateinit var dir: String

    @BeforeTest
    fun setup() {
        dir = uniqueTempDir("store")
        // AttriaxNativeFileKeyValueStore creates the dir in its init.
    }

    @AfterTest
    fun cleanup() {
        // Best-effort: overwrite the store contents so a stale file can't leak into a
        // later run with the same timestamped dir (collisions are effectively
        // impossible, so a hard recursive delete is unnecessary here).
        val store = AttriaxNativeFileKeyValueStore(dir)
        store.remove("k")
        store.remove("k2")
    }

    @Test
    fun putGetRemoveRoundtrip() {
        val store = AttriaxNativeFileKeyValueStore(dir)
        assertNull(store.getString("k"))

        store.putString("k", "v1")
        assertEquals("v1", store.getString("k"))

        store.putString("k", "v2")
        assertEquals("v2", store.getString("k"))

        store.remove("k")
        assertNull(store.getString("k"))

        // putString(null) also removes.
        store.putString("k2", "x")
        store.putString("k2", null)
        assertNull(store.getString("k2"))
    }

    @Test
    fun persistsAcrossAFreshInstanceOnTheSameDir() {
        val first = AttriaxNativeFileKeyValueStore(dir)
        first.putString("attriax.device_id", "abc-123")
        // A value carrying JSON special chars proves the codec round-trips faithfully.
        first.putString("queue", """[{"a":1,"s":"he said \"hi\"\n"}]""")

        // A brand-new instance on the same dir (simulates a process restart).
        val second = AttriaxNativeFileKeyValueStore(dir)
        assertEquals("abc-123", second.getString("attriax.device_id"))
        assertEquals("""[{"a":1,"s":"he said \"hi\"\n"}]""", second.getString("queue"))
    }
}
