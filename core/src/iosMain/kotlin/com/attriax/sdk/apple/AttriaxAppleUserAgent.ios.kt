package com.attriax.sdk.apple

/**
 * iOS Safari-shaped fallback UA (used only when neither a wrapper-supplied UA nor a
 * live WKWebView probe is available — see [AttriaxAppleUserAgent]). A genuine,
 * isbot-passing Mobile Safari UA from which the backend still derives an iPhone + iOS
 * version — NOT the synthetic `attriax-ios-sdk` slug.
 */
internal actual fun attriaxFallbackWebUserAgent(osVersion: String): String {
    val underscored = osVersion.ifBlank { "17_0" }.replace('.', '_')
    val major = osVersion.substringBefore('.').ifBlank { "17" }
    return "Mozilla/5.0 (iPhone; CPU iPhone OS $underscored like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/$major.0 Mobile/15E148 Safari/604.1"
}
