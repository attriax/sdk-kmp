package com.attriax.sdk

/*
 * Platform engine factory for the C-ABI boundary ([AttriaxCApi]).
 *
 * The uniform JSON-dispatch bridge itself is platform-neutral, so it lives in
 * `nativeMain` and is shared by every Kotlin/Native target — the desktop C-ABI
 * dylib/DLL (mingw/linux) AND the Apple targets (the macOS `libattriax_core.dylib`
 * for the Unity dlopen path + the static `AttriaxCore` framework whose `@CName`
 * symbols Unity iOS links via `__Internal`). Only ENGINE CONSTRUCTION differs per
 * platform, so that one seam is expect/actual:
 *   - desktop → [AttriaxDesktopNative] (Ktor transport + POSIX file store),
 *   - apple   → [AttriaxApple] (NSURLSession transport + NSUserDefaults store).
 */

/** Build the platform-native [Attriax] engine for the C-ABI `attriax_create`. */
internal expect fun attriaxNativeCreateEngine(config: AttriaxConfig, dataDir: String?): Attriax

/**
 * Default persistence directory for the platform, or `null` when the platform manages
 * its own storage location (Apple → NSUserDefaults, no host filesystem dir).
 */
internal expect fun attriaxNativeDefaultDataDir(): String?
