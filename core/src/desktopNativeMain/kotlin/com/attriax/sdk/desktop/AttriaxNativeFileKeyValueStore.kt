package com.attriax.sdk.desktop

import com.attriax.sdk.internal.AttriaxProjectScopedKeyValueStore
import com.attriax.sdk.internal.KeyValueStore
import com.attriax.sdk.internal.json.Json
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.remove as posixRemove
import platform.posix.rename

/**
 * Durable, thread-safe [KeyValueStore] for Kotlin/Native desktop (mingwX64 /
 * linuxX64), the native sibling of the JVM
 * [com.attriax.sdk.jvm.AttriaxFileKeyValueStore] and Android's
 * SharedPreferences store. The queue, device identity, and crash-replay all
 * persist through this seam, so it MUST survive a process restart and never
 * corrupt under concurrent access.
 *
 * Contract parity with the JVM store:
 *  - all mutations serialize behind [lock] (atomicfu `SynchronizedObject`, the same
 *    lock primitive the shared engine uses),
 *  - the in-memory map is the source of truth once loaded,
 *  - every write is flushed atomic-ish: serialize to a temp file in the same dir,
 *    then `remove(target)` + `rename(temp → target)`. (`rename` cannot overwrite an
 *    existing file on Windows, so the target is removed first — the same short
 *    delete+rename window the JVM store falls back to when `ATOMIC_MOVE` is
 *    rejected.) A crash mid-write leaves either the old or the new file, never a
 *    torn one.
 *
 * On-disk format is a single JSON object of string→string, encoded with the SDK's
 * dependency-free [Json] codec (the same codec the queue/batching use), so no
 * platform properties-file API is needed.
 *
 * The desktop factory layers TWO instances behind an
 * [AttriaxProjectScopedKeyValueStore]: one on the default [FILE_NAME] (the
 * machine-shared device-identity file, which is also the pre-split legacy store)
 * and one on [projectFileName] (the per-project mutable state) — see #78.
 */
@OptIn(ExperimentalForeignApi::class)
class AttriaxNativeFileKeyValueStore(dir: String, fileName: String = FILE_NAME) : KeyValueStore {

    private val lock = SynchronizedObject()
    private val filePath: String = joinPath(dir, fileName)
    private val tempPath: String = joinPath(dir, "$fileName.tmp")
    private val entries = mutableMapOf<String, String>()

    init {
        attriaxEnsureDir(dir)
        load()
    }

    override fun getString(key: String): String? = synchronized(lock) {
        entries[key]
    }

    override fun putString(key: String, value: String?): Unit = synchronized(lock) {
        if (value == null) {
            entries.remove(key)
        } else {
            entries[key] = value
        }
        persist()
    }

    override fun remove(key: String): Unit = synchronized(lock) {
        entries.remove(key)
        persist()
    }

    /** Snapshot of every key currently in the store (legacy-import enumeration). */
    fun keys(): Set<String> = synchronized(lock) {
        entries.keys.toSet()
    }

    private fun load() {
        val raw = readAllText(filePath) ?: return
        if (raw.isBlank()) return
        try {
            val decoded = Json.decode(raw)
            if (decoded is Map<*, *>) {
                for ((k, v) in decoded) {
                    if (k is String && v is String) entries[k] = v
                }
            }
        } catch (e: Exception) {
            // A corrupt/unreadable store degrades to empty rather than crashing the
            // host; the next write overwrites it atomic-ish.
        }
    }

    private fun persist() {
        try {
            val serialized = Json.encode(entries)
            if (!writeAllText(tempPath, serialized)) return
            // `rename` won't overwrite on Windows — remove the target first, then move
            // the fully-written temp over it (short delete+rename window, parity with
            // the JVM store's non-atomic fallback path). NB: qualify the POSIX call —
            // an unqualified `remove` would bind to this class's own `remove(key)`.
            posixRemove(filePath)
            rename(tempPath, filePath)
        } catch (e: Exception) {
            // Never let a persistence failure crash the host; the in-memory map still
            // serves this process, and the next write retries the flush.
        }
    }

    private fun readAllText(path: String): String? {
        val fp = fopen(path, "rb") ?: return null
        try {
            val bufferSize = 8192
            val collected = ArrayList<Byte>()
            memScoped {
                val buffer = allocArray<ByteVar>(bufferSize)
                while (true) {
                    val read = fread(buffer, 1u, bufferSize.convert(), fp).toInt()
                    if (read <= 0) break
                    for (i in 0 until read) collected.add(buffer[i])
                }
            }
            return collected.toByteArray().decodeToString()
        } finally {
            fclose(fp)
        }
    }

    private fun writeAllText(path: String, text: String): Boolean {
        val bytes = text.encodeToByteArray()
        val fp = fopen(path, "wb") ?: return false
        try {
            if (bytes.isNotEmpty()) {
                val ok = bytes.usePinned { pinned ->
                    val written = fwrite(
                        pinned.addressOf(0),
                        1u,
                        bytes.size.convert(),
                        fp,
                    ).toInt()
                    written == bytes.size
                }
                if (!ok) return false
            }
            fflush(fp)
            return true
        } finally {
            fclose(fp)
        }
    }

    companion object {
        const val FILE_NAME = "attriax-sdk.json"

        /** Per-project store file name (token hashed — never embedded verbatim). */
        internal fun projectFileName(projectToken: String): String =
            "attriax-sdk-p${AttriaxProjectScopedKeyValueStore.projectStoreFileSuffix(projectToken)}.json"
    }
}

/**
 * Join a directory and a file name with the platform separator. Kept trivial: the
 * factory passes a normalized dir, so we only need to insert one separator and
 * tolerate a trailing one.
 */
internal fun joinPath(dir: String, name: String): String {
    val sep = if (dir.contains('\\') && !dir.contains('/')) '\\' else '/'
    val trimmed = dir.trimEnd('/', '\\')
    return "$trimmed$sep$name"
}
