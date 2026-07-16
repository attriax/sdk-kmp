package com.attriax.sdk.internal

import com.attriax.sdk.internal.attestation.attriaxSha256
import com.attriax.sdk.internal.consent.AttriaxConsentStore
import com.attriax.sdk.internal.json.Json
import com.attriax.sdk.internal.session.AttriaxSessionSnapshotStore

/**
 * Desktop-only composite [KeyValueStore] that splits persisted state across TWO
 * physical stores:
 *
 *  - [sharedIdentityStore] — the machine-wide store every Attriax-using app on the
 *    machine shares. It holds ONLY the device-identity keys
 *    ([SHARED_IDENTITY_KEYS]): sharing the device id across desktop apps is a
 *    deliberate, settled decision (#72), and this file is also the pre-split legacy
 *    single-file store, so existing device ids carry forward with zero migration.
 *  - [projectStore] — a per-project-token store (file name derived from
 *    [projectStoreFileSuffix]) holding EVERYTHING else: the request queue, GDPR
 *    consent + consentId, session snapshot, first-launch/crash/referrer state, and
 *    any future key.
 *
 * Why the split exists (#78): desktop factories default `dataDir` to a per-user
 * (not per-app) directory, so two apps integrating the SDK previously shared one
 * mutable file — app A's GDPR consent for project X read as consent for app B's
 * project Y, one app's session snapshot clobbered the other's (producing recovered
 * session-END telemetry for a *foreign* project's session id), and the two
 * processes' whole-file rewrites erased each other's writes. Routing every mutable
 * key to a per-project file removes all cross-project sharing; the shared file is
 * reduced to the write-once device identity, making concurrent whole-file rewrites
 * of it idempotent in practice.
 *
 * RESIDUAL, documented honestly: two processes running with the SAME project token
 * still share one per-project file with last-writer-wins whole-file rewrites and no
 * cross-process lock — same-token concurrent writers can still clobber each other,
 * exactly as before the split. Fixing that would need real file locking or
 * merge-on-write and is out of scope here.
 *
 * The engine sees ONE [KeyValueStore]; routing is invisible to commonMain
 * consumers. Only the desktop factories construct this class.
 */
internal class AttriaxProjectScopedKeyValueStore(
    private val sharedIdentityStore: KeyValueStore,
    private val projectStore: KeyValueStore,
) : KeyValueStore {

    override fun getString(key: String): String? = route(key).getString(key)

    override fun putString(key: String, value: String?) = route(key).putString(key, value)

    override fun remove(key: String) = route(key).remove(key)

    private fun route(key: String): KeyValueStore =
        if (key in SHARED_IDENTITY_KEYS) sharedIdentityStore else projectStore

    /**
     * One-time upgrade path: carry the pre-split legacy single-file state forward
     * into the per-project store, so an app updating the SDK keeps its queue,
     * consent decision, and session. [legacyKeys] is a snapshot of the keys present
     * in the legacy file (which IS [sharedIdentityStore] — same path, same name).
     *
     * Semantics — copy-and-leave, with two deliberate exceptions:
     *
     *  - Values are COPIED, never removed from the legacy file: an older-SDK app on
     *    the same machine may still be reading it, and deleting state another
     *    process might need is worse than one more generation of today's shared
     *    semantics. The trade-off is that every *future* project token's first run
     *    on this machine also imports the frozen legacy values once (consent
     *    decision, first-launch flag, any still-unflushed queue) — that is today's
     *    shipped sharing behavior replayed one last time per project, after which
     *    all writes are isolated.
     *  - [AttriaxConsentStore.KEY_CONSENT_ID] is NEVER imported. The legacy shared
     *    consentId is the value that correlates unrelated projects server-side
     *    (defect #78/2); copying it into per-project files would make that
     *    correlation permanent. Each project mints a fresh consentId on first use
     *    instead. The consent DECISION itself is imported (losing a recorded grant
     *    — or worse, a recorded denial — on upgrade is not acceptable).
     *  - [AttriaxSessionSnapshotStore.KEY_SESSION] is imported only when the
     *    snapshot's `appPackageName` matches this engine's [appPackageName]
     *    (null == null counts as a match, the single-app common case). Importing a
     *    foreign app's snapshot would immediately re-create defect #78/3: the
     *    restore path emits a recovered session-END for the foreign session id to
     *    the wrong project. The owning app keeps its session continuity; a
     *    non-owner simply starts a fresh session.
     *
     * Guarded by [KEY_LEGACY_IMPORT] in the project store so the import runs once
     * per project file — without the marker, state the user later cleared (consent
     * reset, ended session) would silently resurrect from the frozen legacy file on
     * every launch.
     */
    fun importLegacySingleFileState(legacyKeys: Set<String>, appPackageName: String?) {
        if (projectStore.getString(KEY_LEGACY_IMPORT) != null) return
        for (key in legacyKeys) {
            if (key in SHARED_IDENTITY_KEYS || key == KEY_LEGACY_IMPORT) continue
            if (key == AttriaxConsentStore.KEY_CONSENT_ID) continue
            // Never overwrite state this project already wrote.
            if (projectStore.getString(key) != null) continue
            val value = sharedIdentityStore.getString(key) ?: continue
            if (key == AttriaxSessionSnapshotStore.KEY_SESSION &&
                !sessionBelongsToApp(value, appPackageName)
            ) {
                continue
            }
            projectStore.putString(key, value)
        }
        projectStore.putString(KEY_LEGACY_IMPORT, LEGACY_IMPORT_DONE)
    }

    /**
     * True when the legacy session snapshot's `appPackageName` matches this
     * engine's (null-tolerant equality). A corrupt snapshot is not carried forward
     * — the restore path would drop it anyway.
     */
    private fun sessionBelongsToApp(rawSnapshot: String, appPackageName: String?): Boolean =
        try {
            (Json.decodeObject(rawSnapshot)["appPackageName"] as? String) == appPackageName
        } catch (e: Exception) {
            false
        }

    companion object {
        /**
         * The keys that stay machine-wide by design (#72): the device identity.
         * Includes the pre-#64 legacy device-id key so the identity store's own
         * carry-forward migration keeps operating on the shared file.
         */
        val SHARED_IDENTITY_KEYS: Set<String> = setOf(
            AttriaxDeviceIdentityStore.KEY_DEVICE_ID_FALLBACK,
            AttriaxDeviceIdentityStore.KEY_DEVICE_ID_RESOLVED,
            AttriaxDeviceIdentityStore.KEY_DEVICE_ID_SOURCE,
            AttriaxDeviceIdentityStore.LEGACY_KEY_DEVICE_ID,
        )

        /** Per-project marker: the legacy single-file import already ran. */
        const val KEY_LEGACY_IMPORT = "attriax.desktop.legacy_import"
        const val LEGACY_IMPORT_DONE = "done"

        /**
         * Stable per-project file-name suffix: the first 8 bytes (16 hex chars) of
         * SHA-256 of the trimmed project token. Hashed rather than embedded so the
         * secret token never appears verbatim in a file name, and sanitized-by-
         * construction for any filesystem. Same-token apps map to the same file
         * (intended — they are the same project); distinct tokens can collide only
         * with negligible probability, and a collision degrades to today's shared
         * semantics rather than data loss.
         */
        fun projectStoreFileSuffix(projectToken: String): String {
            val digest = attriaxSha256(projectToken.trim().encodeToByteArray())
            return digest.take(8).joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        }
    }
}
