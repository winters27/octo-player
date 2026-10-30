import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// FFM is provided by Java 27. Kotlin currently emits at most Java 25 bytecode.
kotlin {
    jvmToolchain(27)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
}
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

val kdtNative = configurations.create("kdtNative")
val kdtVersion = libs.versions.kotlinDesktopToolkit.get()
dependencies {
    implementation(project(":desktop"))
    implementation(project(":desktop-design"))
    implementation(project(":shared:core"))
    implementation(project(":subsonic"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation("org.jetbrains.kotlin-desktop-toolkit:kotlin-desktop-toolkit-common:$kdtVersion")
    kdtNative("org.jetbrains.kotlin-desktop-toolkit:kotlin-desktop-toolkit-linux-x64:$kdtVersion")
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

val unpackKdt = tasks.register<Sync>("unpackKdt") {
    from(kdtNative.map { zipTree(it) }) { include("**/*.so"); exclude("**/*+debug.so"); eachFile { path = name } }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("kdt"))
}
// The same EGL/Skia bridge as the independently checked GPU probe.
val shareGpu = tasks.register<Sync>("shareGpu") {
    from(rootProject.file("wayland-probe/src/main/kotlin/app/winters/octo/wayland/Gpu.kt"))
    into(layout.buildDirectory.dir("generated/gpu"))
}
sourceSets.main { kotlin.srcDir(shareGpu) }
val mainClassName = "app.winters.octo.wayland.MainKt"
val waylandVersion = providers.gradleProperty("octoDesktopVersion").orElse("1.0.0-wayland-dev")
val buildDetails = tasks.register("buildDetails") {
    val output = layout.buildDirectory.dir("generated/buildDetails")
    inputs.property("version", waylandVersion)
    outputs.dir(output)
    doLast {
        output.get().file("wayland-build.properties").asFile.apply {
            parentFile.mkdirs()
            writeText("version=${waylandVersion.get()}\n")
        }
    }
}
sourceSets.main { resources.srcDir(buildDetails) }
tasks.register<JavaExec>("run") {
    dependsOn(unpackKdt)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(mainClassName)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(27)) })
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-Xms64m", "-Xmx1g")
    environment.remove("DISPLAY")
    systemProperty("kdt.library.folder.path", layout.buildDirectory.dir("kdt").get().asFile.absolutePath)
}
tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true") }

// AndroidX and JetBrains publish some different artifacts under the same jar
// filename (including forwarding jars). Preserve both with content-addressed
// names. The host jar must precede lib/* for the Compose dispatcher adapter.
val installDist = tasks.register<Sync>("installDist") {
    from(configurations.runtimeClasspath) {
        into("lib")
        eachFile {
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            name = digest.take(8).joinToString("") { "%02x".format(it) } + "-" + name
        }
    }
    from(tasks.jar) { rename { "octo-wayland.jar" } }
    from(unpackKdt) { into("lib/kdt") }
    from("packaging/octo-wayland") { filePermissions { unix("rwxr-xr-x") } }
    from(rootProject.file("LICENSE"))
    from("README.md")
    into(layout.buildDirectory.dir("install/Octo-Wayland"))
}

val runtimeDir = layout.buildDirectory.dir("runtime")
val runtimeJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(27)) }
val makeRuntime = tasks.register<Exec>("makeRuntime") {
    val modules = listOf("java.desktop", "java.logging", "java.net.http", "java.management", "java.instrument",
        "java.naming", "java.xml", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "jdk.security.auth", "jdk.charsets", "jdk.localedata")
    inputs.property("modules", modules)
    inputs.property("jdk", runtimeJava.map { it.metadata.javaRuntimeVersion })
    outputs.dir(runtimeDir)
    doFirst {
        check(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in listOf("amd64", "x86_64")) {
            "Build the Wayland release on Linux x86_64"
        }
        delete(runtimeDir)
        commandLine(runtimeJava.get().metadata.installationPath.file("bin/jlink").asFile,
            "--add-modules", modules.joinToString(","), "--strip-debug", "--no-header-files", "--no-man-pages",
            "--compress=zip-6", "--output", runtimeDir.get().asFile)
    }
}
val portableDir = layout.buildDirectory.dir("portable/Octo-Wayland")
val installPortable = tasks.register<Sync>("installPortable") {
    // jlink's legal files are read-only. Recreate this generated staging tree
    // when inputs change rather than trying to overwrite those files in place.
    doFirst { delete(portableDir) }
    from(installDist)
    from(makeRuntime) { into("runtime") }
    into(portableDir)
}
tasks.register<Tar>("packagePortable") {
    dependsOn(installPortable)
    from(portableDir) {
        into("Octo-Wayland")
        // Gradle 9's reproducible archives default every file to 0644, even
        // when Sync preserved its mode. Java and jspawnhelper need execute bits.
        eachFile { if (file.canExecute()) permissions { unix("rwxr-xr-x") } }
    }
    archiveFileName.set(waylandVersion.map { "Octo-Wayland-$it-linux-x64.tar.gz" })
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
