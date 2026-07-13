package com.attriax.sdk.internal.asa

/**
 * Orchestrates the SDK-side Apple Search Ads (AdServices) token capture flow.
 *
 * Faithful port of the Flutter reference `AttriaxAsaTokenManager`
 * (`internal/attriax_asa_token_manager.dart`). Two entry points:
 *  - [captureAndReportIfNeeded] — the AUTO path fired once per runtime from init. It
 *    asks the platform [acquireToken] seam for the AdServices attribution token and,
 *    if one is available, POSTs it via [sendToken]. Everything degrades to a silent
 *    no-op: disabled config, an already-run latch, no token (the off-iOS seam returns
 *    `null`), or any thrown error (acquisition or network) is caught and logged.
 *  - [submit] — the WRAPPER-SUPPLY path (a Flutter/Unity/React-Native iOS plugin that
 *    fetched the token natively). It POSTs an explicitly supplied token on ANY
 *    platform, bypassing the acquire seam and the once-per-runtime latch, still
 *    best-effort.
 *
 * Mirrors the attestation manager's "never break init" invariant: ASA token capture
 * must never block, fail, or otherwise affect init or session.
 */
internal class AttriaxAsaTokenManager(
    private val enabled: Boolean,
    private val acquireToken: () -> String?,
    private val sendToken: (token: String) -> Unit,
    private val logError: (Throwable) -> Unit,
) {
    private var didRun = false

    /**
     * Acquire and report the AdServices token, at most once per runtime. Never throws.
     * Resolves without side effects when disabled, already run, no token is available,
     * or any error occurs.
     */
    fun captureAndReportIfNeeded() {
        if (didRun || !enabled) return
        didRun = true

        try {
            val token = acquireToken()?.trim()
            if (token.isNullOrEmpty()) return
            sendToken(token)
        } catch (error: Throwable) {
            // ASA token capture is best-effort — never let it break init or session.
            logError(error)
        }
    }

    /**
     * Wrapper-supply: POST a natively-fetched ASA token on any platform. Best-effort —
     * a blank token is ignored and any send failure is swallowed.
     */
    fun submit(token: String) {
        val normalized = token.trim()
        if (normalized.isEmpty()) return
        try {
            sendToken(normalized)
        } catch (error: Throwable) {
            logError(error)
        }
    }
}
