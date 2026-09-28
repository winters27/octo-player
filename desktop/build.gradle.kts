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

// The version the installers carry and the app shows. Windows only
// replaces an installed Octo with a higher version, so every build handed
// out must count up: a test build passes -PoctoBuild=<commit count>, as the
// phone's test builds do, and becomes 1.0.<count>. Raise the first two
// numbers for a release.
val desktopVersion = "1.0.${(findProperty("octoBuild") as String?)?.toIntOrNull() ?: 0}"

// The phone app's icon, for the window and the taskbar, and the rounded one
// made from it for the tray.
val shareIcon by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/ic_launcher-playstore.png")) { rename { "octo-icon.png" } }
    from(file("icons/octo.png")) { rename { "octo-tray.png" } }
    into(layout.buildDirectory.dir("generated/appIcon"))
}

// The system library in system-shim/ (media controls and sleep on Windows
// and macOS), built with cargo for this machine and put where JNA looks for
// it on the class path. Linux needs none: it talks D-Bus from the JVM.
// Building without Rust installed: -Pocto.noSystemShim=true leaves it out,
// and the app then runs without media controls.
val hostName: String = System.getProperty("os.name").lowercase()
val hostArch: String = System.getProperty("os.arch").lowercase().let { if (it == "amd64" || it == "x86_64") "x86-64" else if (it == "arm64") "aarch64" else it }
val shimFolder = when {
    hostName.startsWith("windows") -> "win32-$hostArch"
    hostName.startsWith("mac") -> "darwin-$hostArch"
    else -> null
}
val shimFile = if (hostName.startsWith("windows")) "octo_system.dll" else "libocto_system.dylib"
val buildsShim = shimFolder != null && providers.gradleProperty("octo.noSystemShim").orNull != "true"
val shimTarget = layout.buildDirectory.dir("system-shim")

val buildSystemShim by tasks.registering(Exec::class) {
    enabled = buildsShim
    val crate = file("system-shim")
    workingDir = crate
    inputs.dir(crate.resolve("src"))
    inputs.files(crate.resolve("Cargo.toml"), crate.resolve("Cargo.lock"))
    outputs.file(shimTarget.map { it.file("release/$shimFile") })
    commandLine("cargo", "build", "--release", "--locked", "--target-dir", shimTarget.get().asFile.absolutePath)
}

val shareSystemShim by tasks.registering(Sync::class) {
    if (buildsShim) {
        dependsOn(buildSystemShim)
        from(shimTarget.map { it.file("release/$shimFile") }) { into(shimFolder!!) }
    }
    into(layout.buildDirectory.dir("generated/systemShim"))
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
val hostOs = hostName
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
    resources.srcDir(shareSystemShim)
    resources.srcDir(shareAudioEngine)
    kotlin.srcDir(rootProject.file("audio-engine/bindings/kotlin"))
    kotlin.srcDir(shareLyricsText)
}

// Writes the icon files for the window, tray and installers (icons/) from
// the phone app's store icon. Run by hand when the icon changes; the files
// are kept in the repository.
val makeIcons by tasks.registering(JavaExec::class) {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "app.winters.octo.desktop.system.IconFilesKt"
    args(rootProject.file("app/src/main/ic_launcher-playstore.png").absolutePath, file("icons").absolutePath)
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
    // The operating system's password store, window corners, the system
    // library, and the audio engine's bindings (which need JNA 5.12 or newer).
    implementation(libs.jna)
    // D-Bus on Linux: the media controls (MPRIS), notifications and sleep.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.unixsocket)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
}

compose.desktop {
    application {
        mainClass = "app.winters.octo.desktop.MainKt"
        jvmArgs("-Docto.version=$desktopVersion")
        // `-Pocto.checkPlay=build/check/tone.wav` plays a made-up tone once
        // the window opens and prints what the engine says (audio/SoundCheck.kt).
        providers.gradleProperty("octo.checkPlay").orNull?.let { jvmArgs("-Docto.checkPlay=${file(it).absolutePath}") }
        nativeDistributions {
            // MSI for Windows, DMG for macOS, DEB and RPM for Linux.
            //
            // An AppImage is one extra step on Linux, from the app image
            // that createDistributable makes:
            //   ./gradlew :desktop:createDistributable
            //   mkdir -p Octo.AppDir/usr
            //   cp -r desktop/build/compose/binaries/main/app/Octo/* Octo.AppDir/usr/
            //   cp desktop/icons/octo.png Octo.AppDir/octo.png
            //   ln -s usr/bin/Octo Octo.AppDir/AppRun
            // then Octo.AppDir/octo-Octo.desktop (the name the media controls
            // announce) with these lines:
            //   [Desktop Entry]
            //   Type=Application
            //   Name=Octo
            //   Exec=Octo %U
            //   Icon=octo
            //   Categories=AudioVideo;Audio;Player;
            //   MimeType=audio/mpeg;audio/flac;audio/mp4;audio/ogg;audio/opus;audio/wav;x-scheme-handler/octo;
            // and last: appimagetool Octo.AppDir Octo-1.0.0-x86_64.AppImage
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Octo"
            // The installers need a first number above 0 (macOS insists).
            packageVersion = desktopVersion
            description = "A music player for Subsonic, Navidrome and Octo servers"
            vendor = "Winters"
            copyright = "Copyright Winters. Licensed under the GPL, version 3 or later."
            licenseFile = rootProject.file("LICENSE")
            // Only the parts of the Java runtime the app uses: what the
            // suggestRuntimeModules task lists (java.instrument,
            // jdk.security.auth, jdk.unsupported), plus what it cannot see
            // because it is reached by name at run time: elliptic-curve TLS
            // for https servers, name lookups, and XML for D-Bus on Linux.
            modules("java.instrument", "jdk.security.auth", "jdk.unsupported", "jdk.crypto.ec", "java.naming", "java.xml")
            windows {
                iconFile = file("icons/octo.ico")
                menuGroup = "Octo"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                // Keeps upgrades replacing this app rather than installing
                // beside it. Never change it.
                upgradeUuid = "4f7b3c1e-8a52-4d6b-9e0f-2c8d1a7b5e93"
            }
            macOS {
                iconFile = file("icons/octo.icns")
                bundleID = "app.winters.octo"
                appCategory = "public.app-category.music"
                // octo:// links open the app.
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                          <dict>
                            <key>CFBundleURLName</key>
                            <string>app.winters.octo</string>
                            <key>CFBundleURLSchemes</key>
                            <array><string>octo</string></array>
                          </dict>
                        </array>
                    """.trimIndent()
                }
            }
            linux {
                iconFile = file("icons/octo.png")
                packageName = "octo"
                menuGroup = "AudioVideo;Audio;Player"
                appCategory = "AudioVideo"
                shortcut = true
            }
            // "Open with Octo" for audio files, on every system.
            listOf(
                "mp3" to "audio/mpeg",
                "flac" to "audio/flac",
                "m4a" to "audio/mp4",
                "aac" to "audio/aac",
                "ogg" to "audio/ogg",
                "oga" to "audio/ogg",
                "opus" to "audio/opus",
                "wav" to "audio/wav",
                "aiff" to "audio/aiff",
                "aif" to "audio/aiff",
            ).forEach { (extension, mime) ->
                fileAssociation(mime, extension, "Audio file", file("icons/octo.png"), file("icons/octo.ico"), file("icons/octo.icns"))
            }
        }
    }
}

// The app as a zip that runs from wherever it is unpacked, installing
// nothing: build/compose/binaries/main/zip/Octo-<version>-<system>.zip.
val packagePortableZip by tasks.registering(Zip::class) {
    group = "compose desktop"
    description = "Packs the app into a zip that runs without installing."
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    archiveFileName = "Octo-$desktopVersion-$jnaFolder.zip"
    destinationDirectory = layout.buildDirectory.dir("compose/binaries/main/zip")
}
