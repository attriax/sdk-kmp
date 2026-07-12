package com.attriax.sdk

// Apple (iOS/macOS): the NSURLSession + NSUserDefaults engine. Persistence is managed
// by NSUserDefaults (no host filesystem data dir), and the real WKWebView Safari UA is
// resolved by the Apple layer itself when the wrapper supplies none — so [dataDir] is
// unused here and there is no default data dir to report.

internal actual fun attriaxNativeCreateEngine(config: AttriaxConfig, dataDir: String?): Attriax =
    AttriaxApple.create(config)

internal actual fun attriaxNativeDefaultDataDir(): String? = null
