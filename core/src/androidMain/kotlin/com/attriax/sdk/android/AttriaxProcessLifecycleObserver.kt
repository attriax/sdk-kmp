package com.attriax.sdk.android

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.attriax.sdk.internal.session.AttriaxSessionLifecycleManager

/**
 * Thin Android adapter that maps app-wide foreground/background transitions from
 * [ProcessLifecycleOwner] onto the pure [AttriaxSessionLifecycleManager]. This is
 * the ONLY session file that touches an Android framework
 * type, keeping the state machine + heartbeat logic JVM-testable.
 *
 * Mapping (mirrors Flutter's `AppLifecycleState` handling):
 *  - `ON_START` (app enters foreground) → [AttriaxSessionLifecycleManager.handleForeground].
 *  - `ON_STOP`  (app enters background)  → [AttriaxSessionLifecycleManager.handleBackground].
 *
 * `ProcessLifecycleOwner` does not surface a reliable process-detach callback, so
 * a true `end` (process teardown) is exposed via [AttriaxSessionRuntime.onDetach]
 * for the host to invoke from its terminal Activity/Application hooks; here we
 * only cover the observable foreground/background edges.
 *
 * Callbacks are dispatched on the main thread by ProcessLifecycleOwner; the
 * lifecycle manager's own work (enqueue/flush) is fired onto background executors
 * by the engine, so no blocking I/O runs on the main thread.
 */
class AttriaxProcessLifecycleObserver(
    private val lifecycleManager: AttriaxSessionLifecycleManager,
) : DefaultLifecycleObserver {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Register the observer (idempotent-safe on the main thread). */
    fun register() {
        runOnMain { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    }

    /** Unregister the observer (dispose / reset). */
    fun unregister() {
        runOnMain { ProcessLifecycleOwner.get().lifecycle.removeObserver(this) }
    }

    override fun onStart(owner: LifecycleOwner) {
        lifecycleManager.handleForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        lifecycleManager.handleBackground()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
