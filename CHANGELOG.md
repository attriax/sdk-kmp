# Changelog

## 0.6.1

A fix release for a **runtime-breaking defect in the `com.attriax:core-jvm` artifact of 0.6.0**. Only the JVM artifact was affected — `core-android`, `core-mingwx64`, and `core-linuxx64` are unchanged in substance, and Android/Flutter/Unity integrators were never exposed. If you consume the SDK on a plain JVM (the `AttriaxDesktop` entrypoint), upgrade.

### Fixed
- **`com.attriax:core-jvm:0.6.0` was compiled to Java 21 bytecode (class file major version 65) and published with no `org.gradle.jvm.version` metadata.** On a JDK 17 consumer this failed in the worst possible way: the dependency **resolved successfully and the project compiled green**, then the application died at runtime with `UnsupportedClassVersionError ... has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version ... recognizes class file versions up to 61.0` the first time it touched an SDK class. Because the published Gradle module metadata carried no `org.gradle.jvm.version` attribute, Gradle had nothing to reject the under-versioned consumer with at resolution time, so there was no build-time signal at all. **`core-jvm:0.6.1` is Java 17 bytecode (class file 61)** and its `.module` declares `"org.gradle.jvm.version": 17`, so a JDK 17 consumer now gets bytecode it can actually run, and the supported floor is declared in metadata rather than left implicit.
  - Root cause: the `jvm()` target had no explicit Kotlin `jvmTarget`, so it silently inherited the bytecode level of whichever JDK ran Gradle (a JDK 21 host). The build has no Java toolchain configured — by design, it builds on any JDK 17–21 — and the Kotlin Gradle Plugin only derives `org.gradle.jvm.version` from a toolchain, so the attribute was simply absent. Both are now pinned to a single `attriaxJvmTarget = 17` constant that drives the Android target, the JVM target, `compileOptions`, and the published metadata attribute together, so they cannot drift apart again.
  - **`core-android:0.6.0` was never affected** and is verified Java 17 bytecode (class file 61): the Android Gradle Plugin applies its own `jvmTarget`, which insulated the AAR from the missing configuration. This is why every Android-based integration (`sdk-android`, `attriax_flutter_android`, the Unity `androidlib`) built and ran correctly against 0.6.0.

### Known limitation
- **JDK 17 is the floor for `core-jvm`.** Below it (e.g. a Java 11 consumer — never a supported configuration) Gradle now correctly rejects the JVM variant, but because a plain `java` project does not constrain `org.gradle.jvm.environment`, it then silently matches the **Android** variant and puts `core-release.aar` on the compile classpath instead of emitting a "requires Java 17" error. The build still fails — an AAR is not a usable JVM classpath entry — just not with an obvious message. This is strictly better than 0.6.0 (which handed such a consumer Java 21 bytecode that resolved and compiled green, then died at runtime), but it is not a clean diagnostic. Consume this SDK on JDK 17–21.

### Changed
- `AttriaxVersion.PACKAGE_VERSION` is now `0.6.1`, matching the published Maven coordinate. This constant is stamped into the wire User-Agent and onto every session/crash payload as `sdkPackageVersion`, so devices running this build now report `0.6.1` — which is what makes a fixed install distinguishable from a defective `0.6.0` one in analytics.

---

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
