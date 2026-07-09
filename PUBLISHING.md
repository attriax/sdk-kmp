# Publishing `sdk-kmp` (manual / local — no CI)

The KMP core publishes one artifact per target via the `maven-publish` plugin.
Coordinates come from `gradle.properties` (`ATTRIAX_GROUP`, `ATTRIAX_VERSION`).

## Published artifacts (group `com.attriax`, version `0.5.0`)

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

## Publish locally (no credentials, no signing)

```bash
./gradlew :core:publishToMavenLocal
# -> ~/.m2/repository/com/attriax/core*/<version>/
```

This produces `.aar` / `.jar` / `.klib` + `-sources.jar` + `.module` + `.pom` for
every target. Verified green on this host (android + jvm + mingwX64 + linuxX64).

## Publish to a remote repository (manual)

The remote repo + signing are **dormant** until configured — supply them in
`~/.gradle/gradle.properties` or via `-P`, then run `:core:publish`:

```properties
ATTRIAX_PUBLISH_URL=https://<sonatype-central-or-nexus-or-github-packages>
ATTRIAX_PUBLISH_USER=<user>
ATTRIAX_PUBLISH_PASSWORD=<token>
# Signing (required only when a key is present):
signing.keyId=<8-char-key-id>
signing.password=<key-password>
signing.secretKeyRingFile=<path-to-secring.gpg>
```

```bash
./gradlew :core:publishAllPublicationsToAttriaxRepository
```

No CI: run the publish task manually and locally.

## Toolchain

JBR 21 (pinned in `gradle.properties`), Gradle 8.14, Kotlin 2.0.21, AGP 8.7.3,
compileSdk 35 / minSdk 21. See `README.md` for the target/build matrix.
