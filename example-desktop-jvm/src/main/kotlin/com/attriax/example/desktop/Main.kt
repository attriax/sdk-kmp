package com.attriax.example.desktop

import com.attriax.sdk.AttriaxConfig
import com.attriax.sdk.AttriaxDesktop
import com.attriax.sdk.AttriaxSynchronizationState
import com.attriax.sdk.jvm.AttriaxJvmBrowserOpener

/**
 * Minimal runnable example of embedding the Attriax KMP core on a JVM desktop
 * host (Windows / macOS / Linux, any JVM) via the [AttriaxDesktop] entrypoint.
 *
 * See `docs/features/desktop-jvm-native-sdk-kmp.md` in the root workspace repo
 * for the full desktop-embedding reference (this example follows it closely).
 * The Kotlin/Native sibling entrypoint is [com.attriax.sdk.AttriaxDesktopNative]
 * — same shape, native adapters instead of JVM ones (see the doc's second
 * section); this module only wires the JVM path.
 *
 * Run it with:
 * ```
 * ./gradlew :example-desktop-jvm:run
 * ```
 *
 * Configuration is read from environment variables so no real project token is
 * ever hardcoded here:
 *  - `ATTRIAX_API_BASE_URL` — defaults to a local placeholder; point it at a
 *    real Attriax API instance to see live requests succeed.
 *  - `ATTRIAX_PROJECT_TOKEN` — defaults to a placeholder; requests will be
 *    rejected/queued until you supply a real project token.
 *  - `ATTRIAX_DEBUG_LOGS` — `0` turns `enableDebugLogs` off. SDK diagnostics print as
 *    `[Attriax][LEVEL] ...`; with debug off only `WARNING`/`ERROR` survive, which is
 *    the severity contract every platform sink honors.
 *
 * Without a reachable API this example still runs to completion: the SDK
 * queues every request locally and the synchronization listener below reports
 * `OFFLINE` instead of `SYNCHRONIZED` — that degrade-gracefully behavior is
 * itself part of what the example demonstrates.
 */
fun main() {
    val apiBaseUrl = System.getenv("ATTRIAX_API_BASE_URL") ?: "http://localhost:3000"
    val projectToken = System.getenv("ATTRIAX_PROJECT_TOKEN") ?: "REPLACE_WITH_YOUR_PROJECT_TOKEN"
    // Set ATTRIAX_DEBUG_LOGS=0 to see the severity contract: debug/info go quiet while
    // warnings/errors (e.g. an unreachable API) still surface.
    val enableDebugLogs = System.getenv("ATTRIAX_DEBUG_LOGS") != "0"

    // 1. Configure the SDK. `gdprEnabled = true` so the GDPR consent call below
    //    actually gates tracking (it is a no-op decision when GDPR is off).
    val config = AttriaxConfig(
        projectToken = projectToken,
        apiBaseUrl = apiBaseUrl,
        appVersion = "1.0.0",
        appPackageName = "com.attriax.example.desktop",
        enableDebugLogs = enableDebugLogs,
        gdprEnabled = true,
    )

    // 2. Build the JVM desktop engine. `dataDir` defaults to `~/.attriax`
    //    (falls back to the working directory when no user.home is set) — pass
    //    a custom File here if your app wants an app-specific data directory.
    val attriax = AttriaxDesktop.create(config, dataDir = AttriaxDesktop.defaultDataDir())

    // 3. Observe the runtime synchronization state BEFORE init so no transition
    //    is missed. This is how a desktop host learns whether the SDK is
    //    actually reaching the network — it reflects the connectivity monitor
    //    under the hood (offline/online polling) without the host needing to
    //    query connectivity itself.
    attriax.synchronization.addStateListener { state ->
        println("[attriax] synchronization state -> $state")
        if (state == AttriaxSynchronizationState.OFFLINE) {
            println("[attriax] no reachable API at $apiBaseUrl - requests stay queued locally.")
        }
    }

    // 4. Bootstrap the runtime: restores persisted state, resolves/creates the
    //    device identity, and schedules the app-open signal.
    attriax.init()
    println("[attriax] initialized. isFirstLaunch=${attriax.isFirstLaunch} deviceId=${attriax.deviceId}")

    // 5. Record a plain event.
    attriax.tracking.recordEvent(
        name = "level_complete",
        eventData = mapOf("level" to 3, "score" to 4200),
    )

    // 6. Record a purchase. Standardized revenue helpers lower to recordEvent
    //    under a reserved event name — there is no separate revenue endpoint.
    attriax.tracking.recordPurchase(
        revenue = 4.99,
        currency = "USD",
        productId = "sku_pro_upgrade",
        transactionId = "txn_example_001",
    )

    // 7. Exercise the GDPR consent surface. Consent decisions apply locally
    //    immediately and sync to Attriax in the background; until a decision is
    //    made (or marked not required), identified tracking is held back per
    //    the configured anonymous-tracking policy.
    println("[attriax] needs GDPR consent (local only)? ${attriax.consent.gdpr.needsConsent(localOnly = true)}")
    attriax.consent.gdpr.setConsent(analytics = true, attribution = true, adEvents = false)
    println("[attriax] GDPR consent state -> ${attriax.consent.gdpr.state}")

    // 8. Desktop browser-open (deep-link browser fallback). The SDK
    //    calls this seam itself when a resolved deep link carries a
    //    `browserAction` and `automaticBrowserHandling` is on (the config
    //    default); it is shown here directly so the mechanism is visible.
    //    Opt-in via an env var so running this example headlessly/in CI never
    //    pops a real browser window.
    if (System.getenv("ATTRIAX_EXAMPLE_OPEN_BROWSER") == "1") {
        val opened = AttriaxJvmBrowserOpener().open("https://attriax.com")
        println("[attriax] browser-open dispatched: $opened")
    } else {
        println("[attriax] skipping browser-open demo (set ATTRIAX_EXAMPLE_OPEN_BROWSER=1 to try it).")
    }

    // 9. Best-effort flush of anything still queued, then give the background
    //    flush executor a moment to finish before shutting down.
    attriax.flush()
    Thread.sleep(1_000)

    // 10. Clean shutdown: unregisters the connectivity listener, deactivates
    //     the session heartbeat, and stops the background executors.
    attriax.dispose()
    println("[attriax] disposed. Example complete.")
}
