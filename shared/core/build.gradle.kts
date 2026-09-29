import java.util.Base64

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

// A rehearsal of the desktop update on one PC (scripts/rehearse-update.ps1):
// -PoctoRehearsalKey=<base64 public key> adds a throwaway key to the keys
// this build's desktop app trusts, in the built copy of trusted-keys.txt
// only; the file in the repository never changes. Only a rehearsal version
// may carry it (-PoctoDesktopVersion=1.1.0-rehearsal.1), and
// release_assets.py refuses to publish any rehearsal version.
val rehearsalKey = providers.gradleProperty("octoRehearsalKey").orNull?.trim()?.takeIf(String::isNotEmpty)
if (rehearsalKey != null) {
    val version = providers.gradleProperty("octoDesktopVersion").orNull?.trim().orEmpty()
    if (!Regex("""\d+\.\d+\.\d+-rehearsal[0-9A-Za-z.-]*""").matches(version)) {
        throw GradleException("octoRehearsalKey is only for a rehearsal build: -PoctoDesktopVersion must look like 1.1.0-rehearsal.1, not \"$version\"")
    }
    if (providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
        throw GradleException("octoRehearsalKey is for one PC, never for a build on GitHub")
    }
    val raw = runCatching { Base64.getDecoder().decode(rehearsalKey) }.getOrNull()
    if (raw == null || raw.size != 32) throw GradleException("octoRehearsalKey must be the base64 of an Ed25519 public key's 32 bytes")
}
tasks.named<ProcessResources>("desktopProcessResources") {
    inputs.property("octoRehearsalKey", rehearsalKey.orEmpty())
    if (rehearsalKey != null) {
        doLast {
            destinationDir.resolve("app/winters/octo/update/trusted-keys.txt").appendText("\n# A throwaway key for a rehearsal on one PC (-PoctoRehearsalKey).\n$rehearsalKey\n")
        }
    }
}
