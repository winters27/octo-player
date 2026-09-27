import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The desktop app for Windows, macOS and Linux, on the shared core. For now
// a single window that proves the shared code runs on the desktop JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared:core"))
    // Compose for the machine this builds on; installers are built per OS.
    implementation(compose.desktop.currentOs)
    testImplementation(libs.junit)
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
