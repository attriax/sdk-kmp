@file:OptIn(ExperimentalForeignApi::class)

package com.attriax.sdk

import com.attriax.sdk.desktop.uniqueTempDir
import com.attriax.sdk.internal.contract.AttriaxDispatchContract
import com.attriax.sdk.internal.json.Json
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reflect-the-engine guard for the C-ABI dispatch boundary.
 *
 * Drives the REAL exported `attriax_dispatch` (`route()` in `AttriaxCApi.kt`) with an
 * empty-project-token engine and asserts that EVERY name in
 * [AttriaxDispatchContract.METHODS] reaches a real engine action — i.e. none returns
 * the `unimplemented:<method>` envelope. A case dropped from `route()` (or a name
 * added to the contract without being wired) FAILS here, so the C-ABI dispatch can
 * never silently drift from the canonical contract. A deliberately-bogus method is
 * asserted to be `unimplemented:` to prove the mechanism is live.
 *
 * Network-free by construction: the project token is empty, so `flush` / the app-open
 * resolve to `disabled` and never hit the wire; the state-mutating methods
 * short-circuit on `requireInitialized()` (they are driven BEFORE `init`); and
 * `validateReceipt` / `submitAsaToken` / arg-required methods return their
 * `missing:*` guards before any transport call. `getSessionReferrer` is verified
 * structurally by the contract only — it BLOCKS on the initial-deep-link probe by
 * design, so it is intentionally not live-driven here.
 */
class AttriaxCApiDispatchContractTest {

    @Test
    fun everyContractMethodIsRoutedThroughTheRealCApi() {
        val dir = uniqueTempDir("capi-contract")
        memScoped {
            val handle = attriaxCreate("{}".cstr.ptr, dir.cstr.ptr)
            assertNotNull(handle, "attriax_create returned null — cannot exercise dispatch")
            try {
                // `init` (then `dispose`) are driven last so the engine stays
                // uninitialized while the state-mutating methods run — they hit
                // `requireInitialized()` and never reach the network.
                // `getSessionReferrer` is excluded: it blocks on the initial-deep-link
                // probe by design, so it is verified structurally by the contract only.
                val drivenLast = listOf("init", "dispose")
                val notLiveDriven = "getSessionReferrer"

                for (method in AttriaxDispatchContract.METHODS) {
                    if (method in drivenLast || method == notLiveDriven) continue
                    assertRouted(handle, method)
                }
                for (method in drivenLast) assertRouted(handle, method)

                // Sanity: an unknown method IS reported unimplemented (proves the guard
                // would actually catch a missing case rather than passing everything).
                val bogus = errorOf(dispatch(handle, "definitelyNotARealMethod"))
                assertTrue(
                    bogus != null && bogus.startsWith("unimplemented:"),
                    "expected an unimplemented envelope for a bogus method, got: $bogus",
                )
            } finally {
                attriaxDestroy(handle)
            }
        }
    }

    private fun MemScope.assertRouted(handle: COpaquePointer?, method: String) {
        val error = errorOf(dispatch(handle, method))
        if (error != null && error.startsWith("unimplemented:")) {
            fail(
                "C-ABI route() has no case for contract method '$method'. " +
                    "Either wire it in AttriaxCApi.route() or remove it from " +
                    "AttriaxDispatchContract.METHODS.",
            )
        }
    }

    /** Dispatch [method] with an empty args object and return the raw envelope JSON. */
    private fun MemScope.dispatch(handle: COpaquePointer?, method: String): String {
        val result: CPointer<ByteVar>? = attriaxDispatch(handle, method.cstr.ptr, "{}".cstr.ptr)
        val json = result?.toKString() ?: ""
        attriaxFreeString(result)
        return json
    }

    /** The `error` field of a `{"ok":false,"error":...}` envelope, or null on success. */
    private fun errorOf(envelope: String): String? {
        val map = Json.decode(envelope) as? Map<*, *> ?: return null
        return map["error"] as? String
    }
}
