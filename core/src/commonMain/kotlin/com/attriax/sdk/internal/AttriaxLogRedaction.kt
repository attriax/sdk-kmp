package com.attriax.sdk.internal

import com.attriax.sdk.internal.deeplink.AttriaxUri

/**
 * Render [url] in a form that is safe to write to a device log.
 *
 * Deep links are the SDK's most PII-dense values: a resolved link routinely carries
 * campaign/attribution parameters and can carry user ids, emails, invite codes, or
 * one-time tokens. Every part of a URL after the authority is attacker- or
 * partner-controlled, so this keeps ONLY `scheme://host` and drops:
 *  - the query string (`?token=...`) — the densest carrier of user data,
 *  - the path (`/invite/abc123`) — routinely carries ids and one-time codes,
 *  - the fragment (`#...`), and
 *  - user-info credentials (`user:pass@`) plus the port, which [AttriaxUri.parse]
 *    already strips off the authority for us.
 *
 * Scheme + host is enough to answer the question these logs exist to answer — "which
 * link failed to open, and was it even the link I expected?" — without shipping the
 * payload. The full URL is NEVER logged at any level, including debug: `enableDebugLogs`
 * is a config flag that a release build can legitimately turn on, so it is not a
 * sufficient gate for user data.
 */
internal fun attriaxRedactUrl(url: String?): String {
    if (url.isNullOrBlank()) return "(no URL)"
    val uri = AttriaxUri.parse(url) ?: return "(unparseable URL)"
    val scheme = uri.scheme ?: return "(URL with no scheme)"
    val host = uri.host
    // Authority form (`https://host/...`) vs. custom-scheme form (`myapp:open/thing`),
    // which has no host to show.
    return if (!host.isNullOrEmpty()) "$scheme://$host/[redacted]" else "$scheme:[redacted]"
}
