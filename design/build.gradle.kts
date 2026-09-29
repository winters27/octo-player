plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.winters.octo.design"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
    // The icon vectors live in this module.
    androidResources { enable = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.haze)
    api(libs.haze.blur)
    // The playlist covers' design, which PlaylistArt.kt paints.
    implementation(project(":shared:core"))
    // Back closes sheets.
    implementation(libs.androidx.activity.compose)
}
