package com.attriax.sdk.internal

// Apple Search Ads / AdServices is Apple-only. The currently-built native targets
// (mingwX64/linuxX64) have no AAAttribution framework, so this is always null → the
// ASA token manager makes no submission. The future iosMain actual (deferred to Mac)
// replaces this with the real AAAttribution.attributionToken() call.
internal actual fun attriaxFetchAsaAttributionToken(): String? = null
