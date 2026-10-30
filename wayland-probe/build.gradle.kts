plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    application
}

// Kotlin 2.4 can run on JDK 27 but does not emit Java 27 bytecode yet.
// KDT's FFM API needs a recent JDK; emit 25 bytecode and execute on 27.
kotlin {
    jvmToolchain(27)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
}
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

val kdtVersion = libs.versions.kotlinDesktopToolkit.get()
val kdtNative = configurations.create("kdtNative")
dependencies {
    implementation(project(":shared:core"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlin-desktop-toolkit:kotlin-desktop-toolkit-common:$kdtVersion")
    kdtNative("org.jetbrains.kotlin-desktop-toolkit:kotlin-desktop-toolkit-linux-x64:$kdtVersion")
}

// Compile the real production renderer, rather than a simplified copy.
val shareWash = tasks.register<Sync>("shareWash") {
    from(rootProject.file("desktop/src/main/kotlin/app/winters/octo/desktop/player/wash")) {
        include("WashRenderer.kt")
    }
    into(layout.buildDirectory.dir("generated/wash"))
}
kotlin.sourceSets.main { kotlin.srcDir(shareWash) }

val unpackKdt = tasks.register<Sync>("unpackKdt") {
    from(kdtNative.map { zipTree(it) }) { include("**/libdesktop_linux_x64.so") }
    eachFile { path = name }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("kdt"))
}

application {
    mainClass.set("app.winters.octo.wayland.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
}

tasks.named<JavaExec>("run") {
    dependsOn(unpackKdt)
    environment.remove("DISPLAY")
    systemProperty("kdt.library.folder.path", unpackKdt.get().destinationDir.absolutePath)
    systemProperty("kdt.native.log.path", layout.buildDirectory.file("kdt.log").get().asFile.absolutePath)
}

// The launcher beside the native library makes strace/perf runs independent
// of Gradle. Linux x86_64 only; this is not one of the release packages.
distributions.main {
    contents { from(unpackKdt) { into("lib/kdt") } }
}
