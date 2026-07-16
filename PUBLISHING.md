# Publishing `sdk-kmp` (manual / local — no CI)

The KMP core publishes one artifact per target via the `maven-publish` plugin.
Coordinates come from `gradle.properties` (`ATTRIAX_GROUP`, `ATTRIAX_VERSION`).

**Status: LIVE on Maven Central.** `com.attriax:core` (and its per-target
siblings) is published and resolvable from Maven Central — this is the primary
path every consumer SDK now uses. `publishToMavenLocal` below is retained only
for **local development** against an unreleased core.

## Consume from Maven Central (primary path)

Consumers add `mavenCentral()` to their repositories and depend on the
variant-aware root coordinate — Gradle resolves the right per-target artifact
automatically:

```kotlin
repositories { mavenCentral() }

dependencies {
    implementation("com.attriax:core:0.6.1") // or `api(...)` to re-expose the surface
}
```

> **JVM consumers must not use `0.6.0`.** `core-jvm:0.6.0` shipped Java 21 bytecode with
> no `org.gradle.jvm.version` metadata — a JDK 17 consumer resolves and compiles green,
> then fails at runtime with `UnsupportedClassVersionError`. Use **0.6.1** or newer.
> (`core-android:0.6.0` was never affected.) See `CHANGELOG.md`.

Registry: <https://central.sonatype.com/artifact/com.attriax/core> (per-target
artifacts at `/core-jvm`, `/core-android`, `/core-mingwx64`, `/core-linuxx64`).

## Published artifacts (group `com.attriax`, version `0.6.1`)

| Coordinate | Kind | Consumed by |
| --- | --- | --- |
| `com.attriax:core:<v>` | Gradle-metadata root (variant-aware) | Any Gradle KMP consumer — resolves the right target automatically |
| `com.attriax:core-android:<v>` | Android **AAR** | Native Android apps; the Flutter/Unity Android plugins |
| `com.attriax:core-jvm:<v>` | **JAR** | JVM desktop apps; any JVM host |
| `com.attriax:core-mingwx64:<v>` | Windows-native **klib** | Kotlin/Native consumers; desktop FFI (Windows) |
| `com.attriax:core-linuxx64:<v>` | Linux-native **klib** | Kotlin/Native consumers; desktop FFI (Linux) |

macOS/iOS targets (`core-macosx64`/`-macosarm64`, `core-iosarm64`/…, and an
XCFramework) are produced **at the Mac** once the Apple targets are added.

> Note: the base artifactId is the Gradle module name (`core`). Renaming it to
> `attriax-sdk` before a real publish is a one-line module rename — deliberately
> deferred so it doesn't churn the verified local-publish setup.

## Publish locally for development (no credentials, no signing)

For iterating on the core against a consumer before a Central release, publish to
the local Maven cache (`mavenLocal()`); consumers that add `mavenLocal()` ahead of
`mavenCentral()` will pick it up:

```bash
./gradlew :core:publishToMavenLocal
# -> ~/.m2/repository/com/attriax/core*/<version>/
```

This produces `.aar` / `.jar` / `.klib` + `-sources.jar` + `.module` + `.pom` for
every target. Verified green on this host (android + jvm + mingwX64 + linuxX64).
For released consumption, use Maven Central (above) instead.

## Publish to Maven Central (manual)

Publishing goes through the **Sonatype Central Portal** via the
`com.vanniktech.maven.publish` plugin (see `core/build.gradle.kts`), which signs every
publication and uploads them as one deployment. Credentials live in your **global**
`~/.gradle/gradle.properties` (never committed):

```properties
mavenCentralUsername=<Central Portal user token name>
mavenCentralPassword=<Central Portal user token value>
signingInMemoryKey=<ascii-armored GPG secret key, newlines as \n>
signingInMemoryKeyId=<8-char key id>
signingInMemoryKeyPassword=<key password>
```

```bash
./gradlew :core:publishToMavenCentral
```

`automaticRelease = false`, so the deployment lands **Validated** on
<https://central.sonatype.com/publishing/deployments> for you to review and click
**Publish** manually. Use `:core:publishAndReleaseToMavenCentral` only if you want to
skip that review step.

No CI: run the publish task manually and locally.

## Toolchain

JBR 21 (pinned in `gradle.properties`), Gradle 8.14, Kotlin 2.0.21, AGP 8.7.3,
compileSdk 35 / minSdk 21. See `README.md` for the target/build matrix.
