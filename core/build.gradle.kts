import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    // Applies maven-publish + signing, generates sources/javadoc jars, signs with the
    // in-memory GPG key, and uploads to the Sonatype Central Portal.
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// Manual/local publish coordinates (no CI). `publishToMavenLocal` needs no
// credentials; the remote repo + signing stay dormant unless configured.
group = providers.gradleProperty("ATTRIAX_GROUP").getOrElse("com.attriax")
version = providers.gradleProperty("ATTRIAX_VERSION").getOrElse("0.6.0")

kotlin {
    // Auto-creates the intermediate source sets (nativeMain shared by mingwX64 +
    // linuxX64, appleMain later for iOS/macOS, etc.).
    applyDefaultHierarchyTemplate()

    // JVM-family targets: Android (AAR) + a plain JVM (desktop hosts that embed a
    // JVM, and the fast commonTest runner).
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Publish the release AAR variant (KMP wires the Android publication).
        publishLibraryVariants("release")
    }
    jvm()

    // Native desktop targets buildable on this Windows host. macOS + iOS targets
    // are added at the Mac (they require Xcode); ignoreDisabledTargets keeps the
    // build green here.
    //
    // Each native desktop target ALSO emits a C-ABI shared library
    // (`attriax_core.dll` on Windows / `libattriax_core.so` on Linux) plus the
    // generated C header (`libattriax_core_api.h`) so desktop wrappers (Flutter FFI,
    // Unity P/Invoke) can load the engine through the `@CName`-exported uniform
    // JSON-dispatch bridge in `nativeMain/.../AttriaxCApi.kt` (G1). The klib
    // publications are unaffected — this is an ADDITIONAL output per target.
    mingwX64 {
        // OS CSPRNG (BCryptGenRandom) for the desktop secure-random seam — bcrypt.dll
        // ships with Windows, so it links cleanly on this host.
        compilations.getByName("main") {
            cinterops {
                create("bcrypt") {
                    defFile(project.file("src/nativeInterop/cinterop/bcrypt.def"))
                    packageName("attriax.bcrypt")
                }
            }
        }
        binaries {
            sharedLib("attriax_core") {
                baseName = "attriax_core"
            }
        }
    }
    linuxX64 {
        binaries {
            sharedLib("attriax_core") {
                baseName = "attriax_core"
            }
        }
    }

    // Apple targets (iOS device + simulator, macOS). They REQUIRE Xcode, so they are
    // built at the Mac; on the Windows host `kotlin.native.ignoreDisabledTargets`
    // keeps them declared-but-disabled. `applyDefaultHierarchyTemplate()` above yields
    // the intermediate `appleMain`/`iosMain`/`macosMain` source sets (under
    // `nativeMain`) automatically; the Apple adapters live under `appleMain` while the
    // Windows/Linux desktop-only TRANSPORT code moves to the `desktopNativeMain` set
    // wired below, so Apple never inherits the desktop-only Ktor/POSIX code. The C-ABI
    // JSON-dispatch bridge (`AttriaxCApi`) itself lives in `nativeMain` (shared by all
    // native targets) so the Apple targets export it too — see the macOS sharedLib and
    // the Unity iOS `__Internal` linking below.
    //
    // Each Apple target contributes a STATIC framework slice named `AttriaxCore` to the
    // aggregated XCFramework (consumed by the Flutter iOS plugin + Unity iOS plugin and
    // listed in PUBLISHING.md as a Mac-produced artifact).
    val xcframework = XCFramework("AttriaxCore")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
        macosArm64(),
        macosX64(),
    ).forEach { appleTarget ->
        appleTarget.binaries.framework {
            baseName = "AttriaxCore"
            isStatic = true
            xcframework.add(this)
        }
    }

    // macOS ALSO emits a flat C-ABI shared library (`libattriax_core.dylib`) + generated
    // header (`libattriax_core_api.h`) — the artifact the Unity macOS binding dlopen's,
    // mirroring the mingw/linux desktop dylibs.
    listOf(macosArm64(), macosX64()).forEach { macTarget ->
        macTarget.binaries {
            sharedLib("attriax_core") {
                baseName = "attriax_core"
            }
        }
    }

    // iOS forbids dlopen of an app dylib (IL2CPP statically links), so it ALSO emits a
    // flat C-ABI STATIC library (`libattriax_core.a`) + header per target. A Kotlin/Native
    // FRAMEWORK exports only its Obj-C surface — the top-level `@CName` C functions are
    // NOT in the framework archive — so Unity iOS links this static lib instead and
    // P/Invokes `attriax_create`/`attriax_dispatch`/… via `[DllImport("__Internal")]`. The
    // static framework (above) is retained for the Flutter iOS plugin's Obj-C/Kotlin API.
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { iosTarget ->
        iosTarget.binaries {
            staticLib("attriax_core") {
                baseName = "attriax_core"
            }
        }
    }

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
        // The shared native source set (parent of BOTH the desktop targets and the
        // Apple targets) gets only the platform-neutral coroutines dependency — real
        // off-thread background execution + delay-based scheduling used by the seam
        // actuals that Apple also inherits. The Ktor client (desktop-only transport)
        // moves down to `desktopNativeMain` so the Apple targets never pull it in.
        nativeMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        // Intermediate source set for the Kotlin/Native DESKTOP targets (mingwX64 =
        // Windows-native, linuxX64). Holds the desktop-only engine code — the Ktor
        // transport, the POSIX file store, and the native desktop factory
        // (`AttriaxDesktopNative`, the desktop `attriaxNativeCreateEngine` actual) —
        // plus the Ktor client-core dependency. Sits between `nativeMain` and the two
        // desktop targets so the Apple targets (which descend from `nativeMain` via
        // `appleMain`) never inherit any of it. The C-ABI bridge itself is in
        // `nativeMain`, shared with Apple.
        val desktopNativeMain by creating {
            dependsOn(nativeMain.get())
            dependencies {
                implementation("io.ktor:ktor-client-core:3.0.3")
            }
        }
        val desktopNativeTest by creating {
            dependsOn(nativeTest.get())
        }
        // WinHttp is self-contained on Windows (ships with the OS) — no external
        // native library, links cleanly on this host.
        val mingwX64Main by getting {
            dependsOn(desktopNativeMain)
            dependencies {
                implementation("io.ktor:ktor-client-winhttp:3.0.3")
            }
        }
        // Curl needs libcurl at LINK time. Compiling Kotlin against the engine klib
        // does not require libcurl (the cinterop bindings ship in the artifact), so
        // compileKotlinLinuxX64 stays green on this Windows host; only linking a
        // linuxX64 executable (done at a Linux build) needs libcurl present.
        val linuxX64Main by getting {
            dependsOn(desktopNativeMain)
            dependencies {
                implementation("io.ktor:ktor-client-curl:3.0.3")
            }
        }
        val mingwX64Test by getting { dependsOn(desktopNativeTest) }
        val linuxX64Test by getting { dependsOn(desktopNativeTest) }
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

// KMP publishing to Maven Central via the Sonatype Central Portal (manual/local,
// no CI). vanniktech auto-creates one publication per target (kotlinMultiplatform
// metadata + android AAR + jvm jar + native klibs), attaches sources + javadoc jars,
// signs every publication with the in-memory GPG key (signingInMemoryKey* props),
// and uploads to the Central Portal. `publishToMavenLocal` still needs no credentials
// or signing. Auth: mavenCentralUsername/mavenCentralPassword (the Central user token).
// automaticRelease = false → the deployment lands "Validated" on the portal for you to
// review + click Publish (set true to auto-release once you trust it).
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    signAllPublications()
    coordinates(project.group.toString(), "core", project.version.toString())
    pom {
        name.set("Attriax KMP SDK")
        description.set(
            "Attriax mobile measurement & attribution SDK — one Kotlin Multiplatform " +
                "core for Android, JVM, and native desktop (Windows/Linux/macOS).",
        )
        url.set("https://attriax.com")
        licenses {
            license { name.set("Proprietary") }
        }
        developers {
            developer {
                id.set("attriax")
                name.set("Attriax")
            }
        }
        scm {
            url.set("https://github.com/attriax/sdk-kmp")
            connection.set("scm:git:https://github.com/attriax/sdk-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/attriax/sdk-kmp.git")
        }
    }
}
