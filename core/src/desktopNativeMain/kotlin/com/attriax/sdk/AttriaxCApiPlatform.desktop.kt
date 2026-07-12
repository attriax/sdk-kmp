package com.attriax.sdk

// Desktop (mingw/linux): the Ktor + POSIX-file-store native engine, persisting under
// a host filesystem data dir (default `<home>/.attriax`).

internal actual fun attriaxNativeCreateEngine(config: AttriaxConfig, dataDir: String?): Attriax =
    AttriaxDesktopNative.create(config, dataDir ?: AttriaxDesktopNative.defaultDataDir())

internal actual fun attriaxNativeDefaultDataDir(): String? = AttriaxDesktopNative.defaultDataDir()
