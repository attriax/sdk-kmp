package com.attriax.sdk.apple

import com.attriax.sdk.internal.AttriaxLifecycleBinder
import com.attriax.sdk.internal.session.AttriaxSessionLifecycleManager
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillTerminateNotification
import platform.darwin.NSObjectProtocol

/**
 * iOS [AttriaxLifecycleBinder]: maps `UIApplication` foreground/background/terminate
 * notifications onto the pure [AttriaxSessionLifecycleManager]. The Apple sibling
 * of the Android `AttriaxProcessLifecycleObserver`.
 *
 *  - `didBecomeActive`     (foreground) → [AttriaxSessionLifecycleManager.handleForeground]
 *  - `didEnterBackground`  (background) → [AttriaxSessionLifecycleManager.handleBackground]
 *  - `willTerminate`       (teardown)   → [AttriaxSessionLifecycleManager.handleDetached]
 *
 * This is the ONLY session file that touches UIKit, keeping the state machine +
 * heartbeat logic unit-testable in commonTest.
 */
class AttriaxIosLifecycleBinder(
    private val lifecycleManager: AttriaxSessionLifecycleManager,
) : AttriaxLifecycleBinder {

    private val lock = SynchronizedObject()
    private val observers = mutableListOf<NSObjectProtocol>()

    override fun bind() = synchronized(lock) {
        if (observers.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = null,
        ) { _ -> lifecycleManager.handleForeground() }
        observers += center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null,
        ) { _ -> lifecycleManager.handleBackground() }
        observers += center.addObserverForName(
            name = UIApplicationWillTerminateNotification,
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
