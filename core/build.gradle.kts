import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    // JVM-family targets: Android (AAR) + a plain JVM (desktop hosts that embed a
    // JVM, and the fast commonTest runner).
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()

    // Native desktop targets buildable on this Windows host. macOS + iOS targets
    // are added when at the Mac (they require Xcode); ignoreDisabledTargets keeps
    // the build green here.
    mingwX64()
    linuxX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
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
