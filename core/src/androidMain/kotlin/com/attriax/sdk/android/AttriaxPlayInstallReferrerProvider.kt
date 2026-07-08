package com.attriax.sdk.android

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerDetails
import com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Google Play Install Referrer provider (PARITY §3 — app-open enrichment).
 *
 * This is the ONLY place the real `com.android.installreferrer` API is touched,
 * keeping the capture policy
 * ([com.attriax.sdk.internal.installreferrer.AttriaxInstallReferrerCoordinator])
 * pure and JVM-tested with a fake provider. The Play client is callback-based and
 * must be connected before use; [fetch] bridges that to the coordinator's blocking
 * one-shot seam with a [CountDownLatch] bounded by [connectTimeoutMs]. It is only
 * ever invoked on the engine's flush executor, so blocking is safe.
 *
 * ## Dependency posture (implementation)
 * Unlike attestation (opt-in, default-off, `compileOnly`), install-referrer capture
 * is a DEFAULT-ON core attribution feature ([com.attriax.sdk.AttriaxConfig.installReferrerEnabled]
 * defaults to `true`). Shipping the tiny `com.android.installreferrer:installreferrer`
 * as a normal `implementation` dependency means every integration gets referrer
 * capture with zero extra setup. The `Throwable` catch below is nonetheless
 * belt-and-braces: if the class is somehow absent at runtime the capture degrades
 * to `null` (open sent without referrer) rather than crashing init.
 *
 * ## Device-only truth
 * A meaningful referrer is only returned for an app actually installed from the
 * Play Store; a sideloaded/debug build typically gets `SERVICE_UNAVAILABLE` or an
 * empty referrer. The pure policy is unit-tested; this bridge is device-verified.
 */
class AttriaxPlayInstallReferrerProvider(
    context: Context,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
) : AttriaxInstallReferrerProvider {

    private val appContext: Context = context.applicationContext

    override fun fetch(): AttriaxInstallReferrerDetails? {
        return try {
            val client = InstallReferrerClient.newBuilder(appContext).build()
            val latch = CountDownLatch(1)
            val captured = AtomicReference<AttriaxInstallReferrerDetails?>(null)

            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    try {
                        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                            val details: ReferrerDetails = client.installReferrer
                            captured.set(
                                AttriaxInstallReferrerDetails(
                                    rawReferrer = details.installReferrer,
                                    installBeginTimestampSeconds =
                                        details.installBeginTimestampSeconds,
                                    referrerClickTimestampSeconds =
                                        details.referrerClickTimestampSeconds,
                                    googlePlayInstantParam = details.googlePlayInstantParam,
                                ),
                            )
                        }
                        // FEATURE_NOT_SUPPORTED / SERVICE_UNAVAILABLE / DEVELOPER_ERROR /
                        // PERMISSION_ERROR → leave captured null (no referrer this attempt).
                    } catch (e: Throwable) {
                        // getInstallReferrer() can throw if the service died mid-call.
                    } finally {
                        endConnectionQuietly(client)
                        latch.countDown()
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    // Transient disconnect before setup finished → nothing captured.
                    latch.countDown()
                }
            })

            if (!latch.await(connectTimeoutMs, TimeUnit.MILLISECONDS)) {
                endConnectionQuietly(client)
                return null
            }
            captured.get()
        } catch (e: Throwable) {
            // Play Install Referrer unavailable / dependency missing / build error →
            // degrade to null so capture never breaks the app-open or init.
            null
        }
    }

    private fun endConnectionQuietly(client: InstallReferrerClient) {
        try {
            client.endConnection()
        } catch (e: Throwable) {
            // Already closed / never connected — nothing to do.
        }
    }

    companion object {
        /** Upper bound on the Play-client connection before giving up this attempt. */
        const val DEFAULT_CONNECT_TIMEOUT_MS: Long = 5_000L
    }
}
