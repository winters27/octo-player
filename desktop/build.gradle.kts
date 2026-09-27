import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The desktop app for Windows, macOS and Linux: a client of any Subsonic,
// Navidrome or Octo server, on the shared core and the desktop design.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

// The phone app's icon, for the window and the taskbar.
val shareIcon = tasks.register<Sync>("shareIcon") {
    from(rootProject.file("app/src/main/ic_launcher-playstore.png")) { rename { "octo-icon.png" } }
    into(layout.buildDirectory.dir("generated/appIcon"))
}

// The audio engine: the Rust library in audio-engine/, built for this
// machine by cargo and packed as a JNA resource under JNA's own folder name
// for the system (win32-x86-64, darwin-aarch64, linux-x86-64 and so on), so
// JNA finds it on the class path in `run`, the tests and the installers.
// `-Pocto.audioLib=path/to/lib` uses a library built elsewhere instead,
// for building one system's installer on another. The Kotlin side is the
// bindings audio-engine/scripts/gen-kotlin writes; run it again after
// changing the engine's API.
val engineDir: File = rootProject.file("audio-engine")
val hostOs = System.getProperty("os.name").lowercase()
val jnaFolder = run {
    val os = when {
        hostOs.startsWith("windows") -> "win32"
        hostOs.startsWith("mac") -> "darwin"
        else -> "linux"
    }
    val arch = when (val a = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86-64"
        "aarch64", "arm64" -> "aarch64"
        else -> a
    }
    "$os-$arch"
}
val engineLibrary = when {
    hostOs.startsWith("windows") -> "octo_audio.dll"
    hostOs.startsWith("mac") -> "libocto_audio.dylib"
    else -> "libocto_audio.so"
}
val prebuiltEngine = providers.gradleProperty("octo.audioLib")

val buildAudioEngine = tasks.register<Exec>("buildAudioEngine") {
    description = "Builds the Rust audio engine for this machine."
    workingDir = engineDir
    commandLine("cargo", "build", "--release", "--lib")
    inputs.files(fileTree(engineDir) { include("src/**", "Cargo.toml", "Cargo.lock", "uniffi.toml") })
    outputs.file(File(engineDir, "target/release/$engineLibrary"))
}

val shareAudioEngine = tasks.register<Sync>("shareAudioEngine") {
    if (prebuiltEngine.isPresent) {
        from(prebuiltEngine) {
            rename { engineLibrary }
            into(jnaFolder)
        }
    } else {
        from(buildAudioEngine) {
            include(engineLibrary)
            into(jnaFolder)
        }
    }
    into(layout.buildDirectory.dir("generated/audioEngine"))
}

// The phone app's word-by-word lyrics layout, which uses nothing from
// Android, compiled here from the same file so the two apps lay lyrics out
// alike. The drawing around it is ported in lyrics/FlowingLyrics.kt.
val shareLyricsText = tasks.register<Sync>("shareLyricsText") {
    from(rootProject.file("app/src/main/java/app/winters/octo/player")) { include("FlowingLyricsText.kt") }
    into(layout.buildDirectory.dir("generated/sharedLyrics/kotlin"))
}

sourceSets.main {
    resources.srcDir(shareIcon)
    resources.srcDir(shareAudioEngine)
    kotlin.srcDir(rootProject.file("audio-engine/bindings/kotlin"))
    kotlin.srcDir(shareLyricsText)
}

dependencies {
    implementation(project(":shared:core"))
    implementation(project(":desktop-design"))
    // Compose for the machine this builds on; installers are built per OS.
    implementation(compose.desktop.currentOs)
    // The main thread for coroutines, which Compose and Coil need here.
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    // Covers, cached on disk.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Dragging songs in the queue.
    implementation(libs.reorderable)
    // The operating system's password store, window corners, and the
    // audio engine's bindings (which need JNA 5.12 or newer).
    implementation(libs.jna)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
}

compose.desktop {
    application {
        mainClass = "app.winters.octo.desktop.MainKt"
        // `-Pocto.checkPlay=build/check/tone.wav` plays a made-up tone once
        // the window opens and prints what the engine says (audio/SoundCheck.kt).
        providers.gradleProperty("octo.checkPlay").orNull?.let { jvmArgs("-Docto.checkPlay=${file(it).absolutePath}") }
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Octo"
            // The installers need a first number above 0 (macOS insists).
            packageVersion = "1.0.0"
            vendor = "winters"
        }
    }
}
