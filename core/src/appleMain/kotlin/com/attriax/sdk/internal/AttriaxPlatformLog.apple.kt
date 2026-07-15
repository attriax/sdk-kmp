package com.attriax.sdk.internal

import platform.Foundation.NSLog

/**
 * Apple sink for the [attriaxLogEmit] seam (iOS + macOS).
 *
 * `NSLog` is used deliberately in place of the shared-native `println` this replaces:
 * Kotlin/Native `println` writes to stdout, which the Apple unified log does NOT
 * capture — so SDK lines were invisible in Console.app, `log stream`, and the Xcode
 * console for anything but a simulator run. `NSLog` reaches all three.
 *
 * The line already carries the `[Attriax][LEVEL]` prefix from [AttriaxLogger], so
 * severity stays greppable even though `NSLog` has no priority argument of its own.
 * It is passed as an ARGUMENT to a literal `%s` format rather than as the format
 * string itself — a message containing a `%` (e.g. a URL-encoded deep link) would
 * otherwise be interpreted as a format specifier and read garbage off the stack.
 */
internal actual fun attriaxLogEmit(level: AttriaxLogLevel, line: String) {
    NSLog("%s", line)
}
