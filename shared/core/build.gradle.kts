// The app's logic that does not need Android: song matching, sorting, the
// lyrics engine and parsers, the sound maths, queue rules and the immersive
// background maths. The Android app and the desktop app both build on it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    // The lyrics engine keeps its state in Compose snapshot state; the
    // compiler plugin gives the app the same stability information as before.
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "app.winters.octo.core"
        compileSdk = 37
        minSdk = 29
        // Runs the shared tests on the JVM for Android too.
        withHostTest {}
    }
    jvm("desktop")

    // Both targets run on a JVM, so commonMain is compiled as JVM code: the
    // JDK, OkHttp and JUnit are usable in it. A non-JVM target added later
    // would need an intermediate source set for those parts.
    sourceSets {
        commonMain.dependencies {
            api(project(":subsonic"))
            api(libs.okhttp)
            // The same Compose release as the app's BOM (1.12.1), which the
            // desktop's Compose Multiplatform 1.12.1 also uses.
            api(project.dependencies.platform(libs.compose.bom))
            api(libs.compose.runtime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // Checking the signature on an update (Ed25519). Android's own
            // Ed25519 arrives only in newer versions than the app supports,
            // so both apps use this one.
            implementation(libs.bouncycastle.prov)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mockwebserver)
        }
    }
}
