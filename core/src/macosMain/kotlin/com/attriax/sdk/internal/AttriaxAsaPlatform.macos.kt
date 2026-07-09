package com.attriax.sdk.internal

/**
 * macOS actual for the ASA (AdServices) attribution-token seam. Apple Search Ads /
 * AdServices `AAAttribution` is an iOS/iPadOS framework with no native macOS
 * counterpart, so this always returns `null` → the ASA token manager makes no
 * submission (parity with the desktop stub).
 */
internal actual fun attriaxFetchAsaAttributionToken(): String? = null
