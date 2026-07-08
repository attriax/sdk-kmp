package com.attriax.sdk.internal

// Apple Search Ads / AdServices is Apple-only; the JVM has no AAAttribution
// framework. Always null → the ASA token manager makes no submission.
internal actual fun attriaxFetchAsaAttributionToken(): String? = null
