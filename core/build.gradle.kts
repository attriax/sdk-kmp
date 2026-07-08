import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    // Auto-creates the intermediate source sets (nativeMain shared by mingwX64 +
    // linuxX64, appleMain later for iOS/macOS, etc.).
    applyDefaultHierarchyTemplate()

    // JVM-family targets: Android (AAR) + a plain JVM (desktop hosts that embed a
    // JVM, and the fast commonTest runner).
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()

    // Native desktop targets buildable on this Windows host. macOS + iOS targets
    // are added at the Mac (they require Xcode); ignoreDisabledTargets keeps the
    // build green here.
    mingwX64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            // Multiplatform clock + ISO-8601 formatting — replaces JVM
            // System.currentTimeMillis / SimpleDateFormat in the shared core.
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            // Multiplatform locks + atomics (used as a plain library, no compiler
            // plugin) — replaces java.util.concurrent synchronized/AtomicBoolean.
            implementation("org.jetbrains.kotlinx:atomicfu:0.25.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Kotlin/Native desktop targets (mingwX64 = Windows-native, linuxX64). The
        // shared native source set gets coroutines (real off-thread background
        // execution + delay-based scheduling) and the Ktor client core (suspend HTTP
        // transport, bridged to the synchronous port via runBlocking). Each target's
        // Ktor ENGINE lives in its OWN source set so a Linux-only dependency problem
        // (Curl needs libcurl) can never break the Windows-native build.
        nativeMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("io.ktor:ktor-client-core:3.0.3")
        }
        // WinHttp is self-contained on Windows (ships with the OS) — no external
        // native library, links cleanly on this host.
        val mingwX64Main by getting {
            dependencies {
                implementation("io.ktor:ktor-client-winhttp:3.0.3")
            }
        }
        // Curl needs libcurl at LINK time. Compiling Kotlin against the engine klib
        // does not require libcurl (the cinterop bindings ship in the artifact), so
        // compileKotlinLinuxX64 stays green on this Windows host; only linking a
        // linuxX64 executable (done at a Linux build) needs libcurl present.
        val linuxX64Main by getting {
            dependencies {
                implementation("io.ktor:ktor-client-curl:3.0.3")
            }
        }
        androidMain.dependencies {
            // Back the androidMain adapters (chunk 3): OkHttp transport,
            // ProcessLifecycleOwner binder, Play install-referrer client, and the
            // opt-in Play Integrity attestation provider (compileOnly — only loaded
            // when an integration explicitly enables attestation).
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("androidx.lifecycle:lifecycle-process:2.7.0")
            implementation("com.android.installreferrer:installreferrer:2.2")
            compileOnly("com.google.android.play:integrity:1.4.0")
            compileOnly("com.google.android.gms:play-services-tasks:18.2.0")
        }
    }
}

android {
    namespace = "com.attriax.sdk.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
