# Attriax KMP shared core (`sdk-kmp`)

One **Kotlin Multiplatform** codebase for the Attriax SDK core. It supersedes the
standalone cores: the native **Android** and **iOS** SDKs, and the
**Windows / macOS / Linux** desktop targets, are all thin platform layers over the
shared `commonMain`, and **Flutter** and **Unity** wrap it per platform. The
JS-based SDKs (`sdk-js`, `sdk-react`, `sdk-react-native`) stay on their own
TypeScript core by design — web is a different runtime.

The behaviour reference is `sdk-flutter`; the immediate source of truth for the
port is the **verified** `sdk-android` core (266 device-proven tests). The
`PARITY.md` 44-row matrix in `sdk-android` is the contract for every slice.

## Architecture

Hexagonal. `commonMain` holds all pure logic (engine, tracking, consent/anonymous,
sessions, deep links, request builders, queue, retry) plus the narrow **ports**
(`Ports.kt`: KeyValueStore, HttpClient, ConnectivityMonitor, AttriaxScheduler,
DeviceIdSources, …). Platform edges are `expect`/`actual`:

| seam | commonMain | androidMain | iosMain | desktop (jvm / native) |
| --- | --- | --- | --- | --- |
| storage | `KeyValueStore` port | SharedPreferences | NSUserDefaults | file / Settings |
| http | `HttpClient` port | OkHttp (or Ktor) | Ktor/NSURLSession | Ktor |
| secure random | `expect secureRandomBytes` | SecureRandom | SecRandomCopyBytes | platform RNG |
| clock | port | System | Foundation | System |
| device id | `DeviceIdSources` port | SSAID / GAID | IDFV / IDFA | machine GUID |
| platform signals | — | Play Integrity, Install Referrer | ATT/IDFA/ASA/SKAN/App-Attest | — |

## Targets

Buildable on this Windows host today: `jvm`, `androidTarget`, `mingwX64`
(Windows-native), `linuxX64` (cross). The `iosX64/iosArm64/iosSimulatorArm64`,
`macosX64/macosArm64` targets require macOS + Xcode and are added at the Mac.

## Build

```bash
./gradlew :core:jvmTest              # fast common+jvm test runner
./gradlew :core:mingwX64Test         # Windows-native
./gradlew :core:linuxX64Test         # Linux-native (cross)
./gradlew :core:allTests             # every buildable target
```

Toolchain: JBR 21 (pinned in `gradle.properties`), Gradle 8.14, Kotlin 2.0.21,
AGP 8.7.3, compileSdk 35 / minSdk 21.
