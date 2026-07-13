// Runnable usage example for the AttriaxDesktop (JVM) entrypoint — see
// docs/features/desktop-jvm-native-sdk-kmp.md in the root repo for the full
// desktop-embedding reference. Plain Kotlin/JVM (not multiplatform): a desktop
// host app consuming the KMP core over its `jvm` target looks exactly like this.
plugins {
    kotlin("jvm")
    application
}

// No explicit jvmToolchain here: `:core`'s `jvm()` target itself sets none
// (only its Android target pins JVM_17), so it compiles against whatever JDK
// runs Gradle (JBR 21, pinned in gradle.properties). Pinning a different
// toolchain here would produce a class-file-version mismatch against `:core`'s
// jvm jar at runtime — match it by leaving this on the default too.

dependencies {
    // Gradle resolves the `:core` project dependency to its published `jvm`
    // target variant automatically (KMP module metadata is variant-aware).
    implementation(project(":core"))
}

application {
    mainClass.set("com.attriax.example.desktop.MainKt")
}
