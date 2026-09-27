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
    // The operating system's password store and window corners.
    implementation(libs.jna)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
}

compose.desktop {
    application {
        mainClass = "app.winters.octo.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Octo"
            // The installers need a first number above 0 (macOS insists).
            packageVersion = "1.0.0"
            vendor = "winters"
        }
    }
}
