import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Properties

// The desktop app for Windows, macOS and Linux: a client of any Subsonic,
// Navidrome or Octo server, on the shared core and the desktop design.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Java 25 (LTS): the runtime the installers bundle and the one the app's
// tests run on. The shared modules it builds on are built with 25 too but
// keep Java 17 classes, which the Android app needs.
val appJavaVersion = 25
kotlin { jvmToolchain(appJavaVersion) }

// jlink and jpackage cut the installers' runtime from this JDK. The Compose
// plugin takes the JDK Gradle itself runs on unless told otherwise, which
// would pack a Java 17 runtime around classes made for 25 (and fail at the
// first start).
val appJdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(appJavaVersion) }
// After the plugin's own afterEvaluate, which sets it for every task.
afterEvaluate {
    tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask>().configureEach {
        javaHome.set(appJdk.map { it.metadata.installationPath.asFile.absolutePath })
    }
}

// The version the installers carry and the app shows. Windows only
// replaces an installed Octo with a higher version, so every build handed
// out must count up: a test build passes -PoctoBuild=<commit count>, as the
// phone's test builds do, and becomes 1.0.<count>.
//
// A release (the desktop-v<version> tag's workflow) also passes its version,
// -PoctoDesktopVersion=1.2.0, or 1.3.0-beta.1 for an early one. The app shows and
// compares that; macOS and Linux packages carry its three numbers; and the
// MSI carries the first two with the commit count third, because Windows
// compares only numbers and refuses one it already has: an early version and
// its release (1.3.0-beta.1, 1.3.0) must still differ there. Raise the first
// two numbers above 1.0 for releases, so they install over any test build.
val octoBuild = (findProperty("octoBuild") as String?)?.toIntOrNull()
val releaseVersion = (findProperty("octoDesktopVersion") as String?)?.takeIf(String::isNotBlank)?.trim()
val releaseParts = releaseVersion?.let { version ->
    val match = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?""").matchEntire(version)
        ?: throw GradleException("octoDesktopVersion must look like 1.2.0 or 1.3.0-beta.1, not $version")
    val (major, minor, patch) = match.destructured
    if (major.toInt() !in 1..255 || minor.toInt() > 255) throw GradleException("Windows needs the first number 1 to 255 and the second at most 255: $version")
    if (octoBuild == null || octoBuild > 65535) throw GradleException("A release needs -PoctoBuild=<commit count>, at most 65535, for the MSI's version")
    Triple(major, minor, patch)
}
val desktopVersion = releaseVersion ?: "1.0.${octoBuild ?: 0}"
val packageNumbers = releaseParts?.let { (major, minor, patch) -> "$major.$minor.$patch" } ?: desktopVersion
val msiNumbers = releaseParts?.let { (major, minor, _) -> "$major.$minor.$octoBuild" } ?: desktopVersion

// The one vector every icon and the logo are drawn from, for the app to
// draw its octopus and its icons (window, tray, opening, About) at any size.
val shareIcon by tasks.registering(Sync::class) {
    from(rootProject.file("tools/appicon/octopus.svg")) { rename { "octo-octopus.svg" } }
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

// The helper that keeps NVIDIA's game overlay off Octo, run as an
// administrator from Settings (audio-engine/src/bin/octo-nvidia.rs).
val nvidiaHelper = if (hostOs.startsWith("windows")) "octo-nvidia.exe" else "octo-nvidia"

val buildAudioEngine = tasks.register<Exec>("buildAudioEngine") {
    description = "Builds the Rust audio engine for this machine, and its NVIDIA helper."
    workingDir = engineDir
    commandLine("cargo", "build", "--release", "--lib", "--bin", "octo-nvidia")
    inputs.files(fileTree(engineDir) { include("src/**", "Cargo.toml", "Cargo.lock", "uniffi.toml") })
    outputs.file(File(engineDir, "target/release/$engineLibrary"))
    outputs.file(File(engineDir, "target/release/$nvidiaHelper"))
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

// On Windows the installed app keeps the engine, the system library and
// JNA's own library as plain files in its folder (the app resources,
// $APPDIR/resources), and Main points JNA there. From inside a jar JNA
// copies each one to a new temporary file at every start, 6.5 MB of it,
// which the virus scanner then reads again before it may load (about 250
// ms a start measured, more on a first start). `run` and the tests still
// find them on the class path.
val packsNatives = hostName.startsWith("windows")
val shareAppNatives by tasks.registering(Sync::class) {
    if (packsNatives) {
        from(shareAudioEngine) { include("$jnaFolder/*") }
        // Not with a prebuilt engine: that build has no helper, and Settings
        // then leaves the NVIDIA row out.
        if (!prebuiltEngine.isPresent) from(buildAudioEngine) { include(nvidiaHelper) }
        from(shareSystemShim) { include("$jnaFolder/*") }
        from(configurations.runtimeClasspath.map { jars -> jars.filter { it.name.startsWith("jna-") }.map { zipTree(it) } }) { include("com/sun/jna/$jnaFolder/*") }
        eachFile { path = "common/$name" }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/appNatives"))
}
if (packsNatives) {
    tasks.jar { exclude("$jnaFolder/**") }
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

// Writes the installers' and the systems' icon files (icons/), each size
// drawn from the vector (tools/appicon/octopus.svg). Run by hand when the
// icon changes; the files are kept in the repository.
val makeIcons by tasks.registering(JavaExec::class) {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "app.winters.octo.desktop.system.IconFilesKt"
    args(file("icons").absolutePath)
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
    testImplementation(libs.okhttp.tls)
}

// The client certificate tests open the files in fixtures/gateway/certs.
// A fresh test JVM every 150 classes: the drawing tests' native memory grows
// across the whole suite in one process (about 14 GB on Linux), more than a
// 16 GB build machine has to spare.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("fixtures/gateway/certs"))
    forkEvery = 150
}

// The performance harness (OCTO_PERF=1, perf/LibraryPerfTest.kt) runs each
// time it is asked for, with room for a library of 100,000 songs.
// OCTO_PERF_HEAP tries it under another heap limit, and OCTO_PERF_JVM
// with other JVM options (space between them).
if (providers.environmentVariable("OCTO_PERF").orNull == "1") {
    tasks.test {
        maxHeapSize = providers.environmentVariable("OCTO_PERF_HEAP").orNull ?: "2g"
        providers.environmentVariable("OCTO_PERF_JVM").orNull?.let { jvmArgs(it.split(" ").filter(String::isNotBlank)) }
        outputs.upToDateWhen { false }
    }
}

// The audio files Octo opens, and their types.
val audioTypes = listOf(
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
)

// A copy of the icon for each audio type, named for it: macOS puts every
// type's icon in the app by file name, and one name used twice stops the
// build.
val fileIcons = layout.buildDirectory.dir("generated/fileIcons")
val makeFileIcons by tasks.registering(Sync::class) {
    audioTypes.forEach { (extension, _) -> from(file("icons/octo.icns")) { rename { "audio-$extension.icns" } } }
    into(fileIcons)
}
tasks.matching { it.name == "createDistributable" || it.name.startsWith("package") }.configureEach { dependsOn(makeFileIcons) }

compose.desktop {
    application {
        mainClass = "app.winters.octo.desktop.MainKt"
        jvmArgs("-Docto.version=$desktopVersion")
        // The heap, for `run` and the installers alike. Left to itself the
        // JVM takes a quarter of the machine's memory as its limit and a
        // 64th as its start (8 GB and 512 MB on 32 GB), never gives it back
        // while the app sits idle, and lets the cover cache (a fifth of the
        // limit) grow to 1.6 GB. The perf harness (perf/LibraryPerfTest.kt)
        // measured a 100,000 song library and its index at 152 MB, 110 MB
        // with repeated strings shared, and a refresh holds two for a moment,
        // so 1 GB leaves room for bigger libraries. After two minutes with
        // no collection a full one runs (70 ms with two such libraries
        // held) and hands back what is free past 30%.
        jvmArgs(
            "-Xms64m",
            "-Xmx1g",
            "-XX:+UseG1GC",
            "-XX:+UseStringDeduplication",
            "-XX:G1PeriodicGCInterval=120000",
            "-XX:-G1PeriodicGCInvokesConcurrent",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=30",
        )
        // How much the graphics card cache keeps of what it has drawn, as
        // spares to reuse. Skia frees spares only once this is full and never
        // for being old, and its 256 MB counts well under what they take on
        // the card (Direct3D 12 lays them out in 256 MB blocks freed only
        // when empty, which Windows also charges to the app's memory): with
        // Full screen, the wash and lyrics playing, the app grew to 2.2 GB
        // and on OpenGL kept growing. At 64 MB it stays near 1.27 GB on
        // either, at the same processor use (soaks of 2026-10-20).
        jvmArgs("-Dskiko.gpu.resourceCacheLimit=64m")
        // JNA (the audio engine, the system library) loads its native
        // library through System.load; Java 24 and later warn about that on
        // every start and plan to block it unless the app says it may.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // `-Pocto.checkPlay=build/check/tone.wav` plays a made-up tone once
        // the window opens and prints what the engine says (audio/SoundCheck.kt).
        providers.gradleProperty("octo.checkPlay").orNull?.let { jvmArgs("-Docto.checkPlay=${file(it).absolutePath}") }
        // Octo's Discord application, for showing the song in the listener's
        // Discord status: its id from the Discord developer portal, as
        // octo.discordAppId in gradle.properties (it is public, not a secret)
        // or -Pocto.discordAppId=... . Without one, Settings leaves Discord out.
        providers.gradleProperty("octo.discordAppId").orNull?.takeIf(String::isNotBlank)?.let { jvmArgs("-Docto.discordAppId=${it.trim()}") }
        nativeDistributions {
            // MSI for Windows, DMG for macOS, DEB and RPM for Linux. The
            // Flatpak, AppImage and pacman package are made from the same
            // app image by the tasks at the end of this file.
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Octo"
            // Files beside the jars: the native libraries (shareAppNatives).
            appResourcesRootDir.set(layout.dir(shareAppNatives.map { it.destinationDir }))
            // The installers need a first number above 0 (macOS insists).
            packageVersion = packageNumbers
            description = "A music player for Subsonic, Navidrome and Octo servers"
            vendor = "Winters"
            copyright = "Copyright Winters. Licensed under the GPL, version 3 or later."
            licenseFile = rootProject.file("LICENSE")
            // Only the parts of the Java runtime the app uses: what the
            // suggestRuntimeModules task lists (java.instrument,
            // jdk.security.auth, jdk.unsupported), plus what it cannot see
            // because it is reached by name at run time: elliptic-curve TLS
            // for https servers, name lookups, and XML for D-Bus on Linux.
            // jdk.accessibility carries the Java Access Bridge, the only way
            // a screen reader on Windows (NVDA, JAWS) can read the app;
            // without it the installed Octo is a blank window to them.
            // java.management lets the memory log read the JVM's memory
            // outside the heap and its direct buffers.
            modules("java.instrument", "jdk.security.auth", "jdk.unsupported", "jdk.crypto.ec", "java.naming", "java.xml", "jdk.accessibility", "java.management")
            // Windows only: the Windows certificate store (Windows-ROOT), so
            // a private authority installed there is trusted for the server
            // (server/SystemTrust.kt). macOS's keychains are in java.base.
            if (hostName.startsWith("windows")) modules("jdk.crypto.mscapi")
            windows {
                iconFile = file("icons/octo.ico")
                menuGroup = "Octo"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                // %LOCALAPPDATA%\OctoPlayer, a folder of the program's own.
                // Earlier builds went into %LOCALAPPDATA%\Octo, which holds
                // the cache, and replacing one emptied that whole folder.
                // Not %LOCALAPPDATA%\Programs\Octo: jpackage lists only
                // the install folder and those inside it for removal, and
                // a per-user MSI fails its checks (ICE64) for the Programs
                // folder above it. The app knows this folder as
                // WINDOWS_INSTALL_FOLDER (update/InstallLauncher.kt).
                installationPath = "OctoPlayer"
                // Keeps upgrades replacing this app rather than installing
                // beside it. Never change it.
                upgradeUuid = "4f7b3c1e-8a52-4d6b-9e0f-2c8d1a7b5e93"
                // Windows' own version count (see desktopVersion above).
                msiPackageVersion = msiNumbers
            }
            macOS {
                iconFile = file("icons/octo.icns")
                bundleID = "app.winters.octo"
                appCategory = "public.app-category.music"
                // What the app and its runtime may do under the hardened
                // runtime a signed, notarized app runs with. Signing itself
                // is switched on by the release workflow with Gradle
                // properties (compose.desktop.mac.sign and its identity).
                entitlementsFile.set(file("packaging/macos/entitlements.plist"))
                runtimeEntitlementsFile.set(file("packaging/macos/entitlements.plist"))
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
            // "Open with Octo" for audio files, on every system. macOS keeps
            // each type's icon in the app under its own name, so each gets
            // its own copy (made by makeFileIcons).
            audioTypes.forEach { (extension, mime) ->
                fileAssociation(mime, extension, "Audio file", file("icons/octo.png"), file("icons/octo.ico"), fileIcons.get().file("audio-$extension.icns").asFile)
            }
        }
    }
}

// The octo:// URL scheme in the Windows installer: once the MSI is made,
// packaging/windows/add-url-scheme.vbs adds the scheme's registry keys to
// it (HKCU, as it installs per user), so a family link's "Open in Octo"
// works from the first moment and the keys go when Octo is removed. The
// plugin clears jpackage's resources folder before it runs, so its WiX
// template cannot carry them. macOS has the scheme in its Info.plist
// above. The .deb and .rpm get the same afterwards from
// packaging/linux/add-url-scheme.sh: x-scheme-handler/octo in the menu
// entry, and a post-install step that registers it. The installed app also
// writes its own entry at its first start (system/LinkRegistration.kt), as
// it does on Windows for a moved install.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>()
    .matching { it.name == "packageMsi" || it.name == "packageReleaseMsi" }
    .configureEach {
        val script = file("packaging/windows/add-url-scheme.vbs")
        inputs.file(script)
        doLast {
            destinationDir.get().asFile.listFiles { f -> f.extension == "msi" }?.forEach { msi ->
                val process = ProcessBuilder("cscript", "//nologo", script.absolutePath, msi.absolutePath).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) throw GradleException("Adding the octo:// scheme to ${msi.name} failed: " + output)
                logger.lifecycle(output.trim())
            }
        }
    }

tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>()
    .matching { it.name in setOf("packageDeb", "packageRpm", "packageReleaseDeb", "packageReleaseRpm") }
    .configureEach {
        val script = file("packaging/linux/add-url-scheme.sh")
        inputs.file(script)
        doLast {
            destinationDir.get().asFile.listFiles { f -> f.extension == "deb" || f.extension == "rpm" }?.forEach { pkg ->
                val process = ProcessBuilder("sh", script.absolutePath, pkg.absolutePath).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) throw GradleException("Adding the octo:// scheme to ${pkg.name} failed: " + output)
                logger.lifecycle(output.trim())
            }
        }
    }

// The JDK's security providers and the modules they live in; any other
// name listed (the JDK's own, in java.base) always stays.
val providerModules = mapOf(
    "SunEC" to "jdk.crypto.ec",
    "SunJGSS" to "java.security.jgss",
    "SunSASL" to "java.security.sasl",
    "XMLDSig" to "java.xml.crypto",
    "SunPCSC" to "java.smartcardio",
    "JdkLDAP" to "java.naming",
    "JdkSASL" to "jdk.security.jgss",
    "SunMSCAPI" to "jdk.crypto.mscapi",
    "SunPKCS11" to "jdk.crypto.cryptoki",
)

// Certificates the packaged runtime refuses wherever they appear in a
// chain, its own roots included.
val distrustedCertificates = layout.projectDirectory.file("security/distrusted-certificates.pem")

// A class data sharing (CDS) archive in the packaged runtime: the Java
// classes every start loads, parsed once here into a file the JVM maps at
// start, as a full JDK ships one (jlink leaves it out of a trimmed runtime).
// jlink's own --generate-cds-archive needs the runtime's java launcher,
// which the trimmed runtime drops, so the JDK that built the runtime lends
// its launcher for the dump and takes it back. A JVM that cannot use the
// archive starts without it, as before.
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask>().configureEach {
    inputs.file(distrustedCertificates)
    doLast {
        val runtime = destinationDir.get().asFile
        // The JDK's own blocklist: one SHA-256 per certificate, in uppercase
        // hex, which it never matches in lowercase.
        val blockList = File(runtime, "lib/security/blocked.certs")
        val refused = distrustedCertificates.asFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            .map { cert -> MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) } }
        fun blocked() = Properties().apply { blockList.inputStream().use { load(it) } }
        if (refused.isEmpty() || blocked().getProperty("Algorithm") != "SHA-256") throw GradleException("The runtime's blocked.certs is not a SHA-256 list, or there is nothing to add to it.")
        val adding = refused.filterNot(blocked()::containsKey)
        if (adding.isNotEmpty()) blockList.appendText(adding.joinToString("\n", prefix = "\n", postfix = "\n"))
        val taken = blocked()
        val missing = refused.filterNot { fingerprint -> taken.containsKey(fingerprint) }
        if (missing.isNotEmpty()) throw GradleException("The runtime's blocked.certs did not take ${missing.joinToString()}.")
        // The security providers the runtime lists, cut to those it carries.
        // Asked for one that is missing, the JVM looks through every
        // provider on the class path instead, which starts the updater's
        // crypto library as a provider and checks its signed jar: 350 ms of
        // every start, on the thread before the window (measured).
        val release = File(runtime, "release").readText()
        val modules = Regex("MODULES=\"([^\"]*)\"").find(release)?.groupValues?.get(1)?.split(' ')?.toSet().orEmpty()
        // The memory log reads the JVM's own memory through java.management;
        // tests run on a full JDK, so only this check sees it missing here.
        if ("java.management" !in modules) {
            throw GradleException("The runtime lacks java.management (MODULES: ${modules.ifEmpty { setOf("unreadable") }})")
        }
        // Without it the Windows certificate store can't be read, and a
        // private authority installed there goes unseen (SystemTrust.kt).
        if (hostName.startsWith("windows") && "jdk.crypto.mscapi" !in modules) {
            throw GradleException("The runtime lacks jdk.crypto.mscapi (MODULES: ${modules.ifEmpty { setOf("unreadable") }})")
        }
        val security = File(runtime, "conf/security/java.security")
        if (modules.isNotEmpty() && security.isFile) {
            val lines = security.readLines()
            val listed = Regex("""security\.provider\.\d+=(\S+).*""")
            var number = 0
            security.writeText(
                lines.mapNotNull { line ->
                    val name = listed.matchEntire(line)?.groupValues?.get(1) ?: return@mapNotNull line
                    val module = providerModules[name]
                    if (module != null && module !in modules) null else "security.provider.${++number}=${line.substringAfter('=')}"
                }.joinToString("\n", postfix = "\n"),
            )
        }
        val exe = if (hostName.startsWith("windows")) "java.exe" else "java"
        val lent = File(runtime, "bin/$exe")
        File(javaHome.get(), "bin/$exe").copyTo(lent, overwrite = true)
        try {
            lent.setExecutable(true)
            val dump = ProcessBuilder(lent.absolutePath, "-Xshare:dump").redirectErrorStream(true).start()
            val said = dump.inputStream.bufferedReader().readText()
            if (dump.waitFor() != 0) throw GradleException("The runtime's CDS archive could not be made:\n$said")
        } finally {
            lent.delete()
        }
    }
}

// The app as a zip that runs from wherever it is unpacked, installing
// nothing: build/compose/binaries/main/zip/Octo-<version>-<system>.zip.
// It carries portable/octo-portable beside the app's jars, which tells the
// app it is not installed, so it never runs an installer over itself.
val packagePortableZip by tasks.registering(Zip::class) {
    group = "compose desktop"
    description = "Packs the app into a zip that runs without installing."
    dependsOn("createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    from(file("portable/octo-portable")) { into(if (hostName.startsWith("windows")) "Octo/app" else "Octo/lib/app") }
    archiveFileName = "Octo-$desktopVersion-$jnaFolder.zip"
    destinationDirectory = layout.buildDirectory.dir("compose/binaries/main/zip")
}

// The app as a Flatpak bundle, from the same app image as the DEB and RPM:
// build/compose/binaries/main/flatpak/Octo-<version>-linux-x64.flatpak.
// Linux only, with flatpak-builder and Flathub's runtime; the tools and the
// sandbox are in packaging/linux/flatpak/flatpak.py.
val packageFlatpak by tasks.registering(Exec::class) {
    group = "compose desktop"
    description = "Packs the app into a Flatpak bundle (Linux x64, needs flatpak-builder)."
    dependsOn("createDistributable")
    val image = layout.buildDirectory.dir("compose/binaries/main/app/Octo")
    val out = layout.buildDirectory.dir("compose/binaries/main/flatpak")
    inputs.dir(file("packaging/linux/flatpak"))
    outputs.dir(out)
    doFirst {
        if (!hostName.startsWith("linux") || hostArch != "x86-64") throw GradleException("The Flatpak is built on Linux x64.")
        out.get().asFile.deleteRecursively()
    }
    commandLine(
        "python3", file("packaging/linux/flatpak/flatpak.py").absolutePath, "bundle",
        "--image", image.get().asFile.absolutePath, "--version", desktopVersion, "--out", out.get().asFile.absolutePath,
    )
}

// The app as an AppImage and as an Arch Linux package, from the same app
// image again: build/compose/binaries/main/appimage/Octo-<version>-linux-x64.AppImage
// and build/compose/binaries/main/pacman/Octo-<version>-linux-x64.pkg.tar.zst.
// Linux x64 only; packaging/linux/packages.py has the tools each needs.
listOf("AppImage" to "appimage", "Pacman" to "pacman").forEach { (title, kind) ->
    tasks.register<Exec>("package$title") {
        group = "compose desktop"
        description = "Packs the app into ${if (kind == "appimage") "an AppImage" else "an Arch Linux package"} (Linux x64)."
        dependsOn("createDistributable")
        val image = layout.buildDirectory.dir("compose/binaries/main/app/Octo")
        val out = layout.buildDirectory.dir("compose/binaries/main/$kind")
        inputs.dir(image)
        inputs.files(file("packaging/linux/packages.py"), file("packaging/linux/flatpak/app.winters.octo.desktop"))
        inputs.dir(file("packaging/linux/$kind"))
        inputs.property("version", desktopVersion)
        outputs.dir(out)
        doFirst {
            if (!hostName.startsWith("linux") || hostArch != "x86-64") throw GradleException("The $title is built on Linux x64.")
            out.get().asFile.deleteRecursively()
        }
        commandLine(
            "python3", file("packaging/linux/packages.py").absolutePath, kind,
            "--image", image.get().asFile.absolutePath, "--version", desktopVersion, "--out", out.get().asFile.absolutePath,
        )
    }
}

// A release's disk image is only ever made signed (the release workflow
// then notarizes it). A release build without compose.desktop.mac.sign=true
// stops here, rather than make a DMG that Gatekeeper would turn away.
val signsForMac = providers.gradleProperty("compose.desktop.mac.sign").orNull == "true"
tasks.matching { it.name == "packageDmg" || it.name == "packageReleaseDmg" }.configureEach {
    doFirst {
        if (releaseVersion != null && !signsForMac) {
            throw GradleException("A release DMG must be signed: pass -Pcompose.desktop.mac.sign=true with the signing identity, as desktop-installers.yml does.")
        }
    }
}
