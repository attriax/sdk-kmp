package com.attriax.sdk.apple

/**
 * macOS Safari-shaped fallback UA (used only when neither a wrapper-supplied UA nor a
 * live WKWebView probe is available — see [AttriaxAppleUserAgent]). A genuine,
 * isbot-passing desktop Safari UA from which the backend still derives a Mac + macOS
 * version — NOT a synthetic slug.
 */
internal actual fun attriaxFallbackWebUserAgent(osVersion: String): String {
    val underscored = osVersion.ifBlank { "10_15_7" }.replace('.', '_')
    val major = osVersion.substringBefore('.').ifBlank { "14" }
    return "Mozilla/5.0 (Macintosh; Intel Mac OS X $underscored) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/$major.0 Safari/605.1.15"
}
