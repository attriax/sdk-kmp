// Attriax KMP shared core — one Kotlin Multiplatform codebase that powers the
// native Android/iOS SDKs and the Windows/macOS/Linux desktop targets, and is
// wrapped by Flutter & Unity. Mirrors the behaviour of the verified sdk-android
// core (which it supersedes) and the sdk-flutter reference.
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
    id("com.android.library") version "8.7.3" apply false
}
