package com.attriax.sdk.internal

import com.attriax.sdk.AttriaxSynchronizationState
import com.attriax.sdk.AttriaxSynchronizationStateListener
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Internal holder for the runtime synchronization [AttriaxSynchronizationState],
 * driven by the [com.attriax.sdk.Attriax] engine from its real dispatch lifecycle
 * (PARITY — Flutter reference `AttriaxSynchronizer._state` / `setState`,
 * `attriax_synchronizer.dart:62/133-139`).
 *
 * Observer fan-out reuses the same lock-guarded snapshot pattern as
 * [com.attriax.sdk.internal.deeplink.AttriaxDeepLinkManager] — listeners are copied
 * under [lock] and notified outside it (no coroutines). Transitions are deduped:
 * [set] emits only on an actual change (mirrors Flutter's `if (_state == nextState) return`).
 */
internal class AttriaxSynchronizationStateHolder(
    initial: AttriaxSynchronizationState = AttriaxSynchronizationState.INITIALIZING,
) {
    private val lock = SynchronizedObject()
    private val listeners = mutableListOf<AttriaxSynchronizationStateListener>()

    @Volatile private var current: AttriaxSynchronizationState = initial

    val state: AttriaxSynchronizationState get() = current

    val isSynchronized: Boolean
        get() = current == AttriaxSynchronizationState.SYNCHRONIZED

    fun addListener(listener: AttriaxSynchronizationStateListener) {
        synchronized(lock) { listeners.add(listener) }
    }

    fun removeListener(listener: AttriaxSynchronizationStateListener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    /**
     * Transition to [next] and notify listeners. No-op (and no emit) when the state
     * is unchanged. Listeners are snapshotted under [lock] and invoked outside it so
     * a callback can safely add/remove without deadlocking.
     */
    fun set(next: AttriaxSynchronizationState) {
        val recipients = synchronized(lock) {
            if (current == next) return
            current = next
            ArrayList(listeners)
        }
        recipients.forEach { it.onSynchronizationStateChanged(next) }
    }
}
