# Changelog

## 0.6.0

First tracked release of `com.attriax:core`, the shared Kotlin Multiplatform core — the single engine behind every Attriax SDK. The native Android and iOS SDKs, the Windows/macOS/Linux desktop targets, and the Flutter and Unity wrappers are all thin platform layers over this `commonMain`. (`sdk-js` / `sdk-react` / `sdk-react-native` stay on their own TypeScript core by design — web is a different runtime.)

### Added
- Published to **Maven Central**: `com.attriax:core` plus the per-target siblings `core-android` (AAR), `core-jvm` (JAR), `core-mingwx64`, and `core-linuxx64` (Kotlin/Native klibs). `mavenCentral()` is now the primary consumption path — no `mavenLocal()` staging required.
- Targets: Android, JVM, Windows-native (`mingwX64`), Linux-native (`linuxX64`), and Apple (`iosArm64` / `iosSimulatorArm64` / `iosX64` / `macosArm64` / `macosX64`), with the Apple slices aggregated into the `AttriaxCore` XCFramework.
- Canonical `com.attriax.sdk.AttriaxDispatcher.execute` command surface: one dispatch table backing every host binding, so Flutter, Unity, and the C-ABI hosts route through identical semantics. Includes a paired `setCcpaConsent` command.
- C-ABI shared library (`attriax_create` / `attriax_dispatch`) for non-Kotlin hosts, emitted for the desktop native targets and as a static lib for the iOS `__Internal` binding.
- CCPA consent: `doNotSell` / `usPrivacy` seeded from config and overridable at runtime through `consent.ccpa`, emitted top-level on the app-open / batch envelopes (mirrors the existing `attStatus` handling).
- Desktop platform actuals: real connectivity detection and browser-open on `jvm`, `mingwX64`, and `linuxX64`; the secure-random seam is backed by the OS CSPRNG.
- SKAdNetwork conversion-value config pull, public initial-link completion, and an advertising-id supplier override.
- Shared Android manifest permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, `INSTALL_REFERRER`, `AD_ID`) declared once in the core AAR and inherited by every Android consumer via manifest merging.
- A runnable JVM desktop usage example (`example-desktop-jvm`).

### Changed
- `init()` is non-blocking and must stay that way: ATT resolution on init was moved off the init thread. This is a documented core invariant on `Attriax.init()`.

### Fixed
- The Apple bindings could not reach `AttriaxDispatcher.execute`: the internal engine-queue `com.attriax.sdk.internal.dispatch.AttriaxDispatcher` was declared public and collided on simple name with the public `com.attriax.sdk.AttriaxDispatcher` command table. Kotlin/Native's Obj-C export cannot emit two `AttriaxCoreAttriaxDispatcher` classes, so it silently dropped the command object (and `AttriaxDispatchResult`) from the XCFramework. The queue class is now `internal`.
- The C-ABI event-string callback now transfers ownership correctly, rather than handing back memory the caller could not free.
- Crash-flag honesty on the desktop native targets: automatic crash capture is a no-op there, but sat behind a default-`true` config flag and failed silently. The limitation is now documented and logged once at runtime instead of being silently ignored.

---

Earlier releases predate this changelog.
