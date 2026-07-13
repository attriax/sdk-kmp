package com.attriax.sdk

/**
 * Runtime synchronization state exposed from the SDK (observability).
 *
 * Mirrors the Flutter reference `AttriaxSynchronizationState`
 * (`attriax_flutter_platform_interface/lib/src/types.dart:46-54`) exactly, driven
 * by the engine's real dispatch lifecycle:
 *
 *  * [INITIALIZING]  — before [Attriax.init] completes / after [Attriax.reset].
 *  * [SYNCHRONIZING] — a flush has begun (the queue is being drained).
 *  * [DEFERRED]      — dispatch is deferred (consent gate buffers it locally, or
 *                      items remain buffered without a transport failure).
 *  * [SYNCHRONIZED]  — every queued request has been delivered (queue is empty).
 *  * [OFFLINE]       — a transport failure left a non-empty queue.
 *  * [FAILED]        — a request was permanently dropped (terminal / non-retryable).
 *  * [DISABLED]      — tracking is disabled / the project token is empty.
 */
enum class AttriaxSynchronizationState {
    INITIALIZING,
    SYNCHRONIZING,
    DEFERRED,
    SYNCHRONIZED,
    OFFLINE,
    FAILED,
    DISABLED,
}

/**
 * Observer notified whenever the synchronization state transitions to a new value
 * (deduped — never fired for an unchanged state). The stream-free listener model
 * mirrors the deep-link observers; wrappers bridge it to a Stream/Flow.
 */
fun interface AttriaxSynchronizationStateListener {
    fun onSynchronizationStateChanged(state: AttriaxSynchronizationState)
}

/**
 * Public synchronization surface exposed as `attriax.synchronization` (
 * observability). Mirrors the Flutter reference `AttriaxSynchronization`
 * (`attriax_synchronization.dart`): [isSynchronized] + [state] snapshots plus a
 * stream-free observable via [addStateListener]/[removeStateListener].
 *
 * Observers use the same lock-guarded snapshot-fanout pattern as the deep-link
 * listeners — no coroutines, matching the engine's plain-thread model.
 */
class AttriaxSynchronization internal constructor(private val engine: Attriax) {

    /** Whether every queued SDK request has been delivered successfully. */
    val isSynchronized: Boolean get() = engine.isSynchronized

    /** Current runtime synchronization state. */
    val state: AttriaxSynchronizationState get() = engine.synchronizationState

    /**
     * Register a state listener. Broadcast with no buffering (mirrors Flutter's
     * broadcast `states` stream): the listener receives transitions from the point
     * of registration onward — the current state is NOT replayed. Read [state] for
     * the current snapshot.
     */
    fun addStateListener(listener: AttriaxSynchronizationStateListener) =
        engine.addSynchronizationStateListener(listener)

    fun removeStateListener(listener: AttriaxSynchronizationStateListener) =
        engine.removeSynchronizationStateListener(listener)
}
