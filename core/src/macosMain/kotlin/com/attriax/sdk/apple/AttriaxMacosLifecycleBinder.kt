package com.attriax.sdk.apple

import com.attriax.sdk.internal.AttriaxLifecycleBinder
import com.attriax.sdk.internal.session.AttriaxSessionLifecycleManager
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import platform.AppKit.NSApplicationDidBecomeActiveNotification
import platform.AppKit.NSApplicationDidResignActiveNotification
import platform.AppKit.NSApplicationWillTerminateNotification
import platform.Foundation.NSNotificationCenter
import platform.darwin.NSObjectProtocol

/**
 * macOS [AttriaxLifecycleBinder]: maps `NSApplication` active/resign/terminate
 * notifications onto the pure [AttriaxSessionLifecycleManager] (PARITY §3, row S3),
 * the AppKit sibling of the iOS `AttriaxIosLifecycleBinder`.
 *
 *  - `didBecomeActive`  (foreground) → [AttriaxSessionLifecycleManager.handleForeground]
 *  - `didResignActive`  (background) → [AttriaxSessionLifecycleManager.handleBackground]
 *  - `willTerminate`    (teardown)   → [AttriaxSessionLifecycleManager.handleDetached]
 *
 * macOS has no exact "entered background" equivalent, so resign-active is used as the
 * background edge (the app is no longer frontmost) — the closest analog for session
 * heartbeat gating.
 */
class AttriaxMacosLifecycleBinder(
    private val lifecycleManager: AttriaxSessionLifecycleManager,
) : AttriaxLifecycleBinder {

    private val lock = SynchronizedObject()
    private val observers = mutableListOf<NSObjectProtocol>()

    override fun bind() = synchronized(lock) {
        if (observers.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(
            name = NSApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null,
        ) { _ -> lifecycleManager.handleForeground() }
        observers += center.addObserverForName(
            name = NSApplicationDidResignActiveNotification,
            `object` = null,
            queue = null,
        ) { _ -> lifecycleManager.handleBackground() }
        observers += center.addObserverForName(
            name = NSApplicationWillTerminateNotification,
            `object` = null,
            queue = null,
        ) { _ -> lifecycleManager.handleDetached() }
    }

    override fun unbind() = synchronized(lock) {
        val center = NSNotificationCenter.defaultCenter
        observers.forEach { center.removeObserver(it) }
        observers.clear()
    }
}
