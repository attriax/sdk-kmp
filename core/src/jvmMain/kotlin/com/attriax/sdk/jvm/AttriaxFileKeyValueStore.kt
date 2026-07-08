package com.attriax.sdk.jvm

import com.attriax.sdk.internal.KeyValueStore
import java.io.File
import java.util.Properties

/**
 * Durable, thread-safe [KeyValueStore] backed by a single properties file on disk
 * (the JVM-desktop equivalent of Android's
 * [com.attriax.sdk.android.AttriaxSharedPreferencesStore]). The queue, device
 * identity, and crash-replay all persist through this seam, so it MUST survive a
 * process restart and never corrupt under concurrent access.
 *
 * Durability strategy:
 *  - all mutations serialize behind [lock],
 *  - the in-memory [Properties] map is the source of truth once loaded,
 *  - every write is flushed atomically (write to a temp file in the same dir, then
 *    `renameTo`/`Files.move` over the target) so a crash mid-write can never leave a
 *    half-written file — the reader sees either the old or the new file.
 */
class AttriaxFileKeyValueStore(dir: File) : KeyValueStore {

    private val lock = Any()
    private val file: File = File(dir, FILE_NAME)
    private val tempFile: File = File(dir, "$FILE_NAME.tmp")
    private val props = Properties()

    init {
        dir.mkdirs()
        load()
    }

    override fun getString(key: String): String? = synchronized(lock) {
        props.getProperty(key)
    }

    override fun putString(key: String, value: String?) = synchronized(lock) {
        if (value == null) {
            props.remove(key)
        } else {
            props.setProperty(key, value)
        }
        persist()
    }

    override fun remove(key: String) = synchronized(lock) {
        props.remove(key)
        persist()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            file.inputStream().use { props.load(it) }
        } catch (e: Exception) {
            // A corrupt/unreadable store degrades to empty rather than crashing the host;
            // the next write overwrites it atomically.
        }
    }

    private fun persist() {
        try {
            tempFile.outputStream().use { out ->
                props.store(out, "attriax sdk store")
                out.flush()
                out.fd.sync()
            }
            // Atomic replace: prefer Files.move(ATOMIC_MOVE); fall back to delete+rename
            // if the platform/filesystem rejects it.
            try {
                java.nio.file.Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: Exception) {
                if (file.exists()) file.delete()
                tempFile.renameTo(file)
            }
        } catch (e: Exception) {
            // Never let a persistence failure crash the host; the in-memory map still
            // serves this process, and the next write retries the flush.
        }
    }

    companion object {
        const val FILE_NAME = "attriax-sdk.properties"
    }
}
