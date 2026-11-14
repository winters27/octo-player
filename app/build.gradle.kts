plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.winters.octo"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.winters.octo"
        minSdk = 29
        targetSdk = 36
        // A test build passes its number (-PoctoBuild=N), so each one it
        // installs counts as newer than the last and reads as 0.2.0.N.
        // A release (the android-v<version> tag's workflow) also passes its
        // version, -PoctoAndroidVersion=1.2.0 or 1.3.0-beta.1, which the app shows
        // and compares with newer releases; the commit count stays the
        // version code, so every build counts up.
        val testBuild = (project.findProperty("octoBuild") as String?)?.toIntOrNull()
        val release = (project.findProperty("octoAndroidVersion") as String?)?.takeIf(String::isNotBlank)?.trim()
        if (release != null) {
            require(Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?""").matches(release)) { "octoAndroidVersion must look like 1.2.0 or 1.3.0-beta.1, not $release" }
            requireNotNull(testBuild) { "A release needs -PoctoBuild=<commit count> for its version code" }
        }
        versionCode = testBuild ?: 2
        versionName = release ?: if (testBuild != null) "0.2.0.$testBuild" else "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Trying the updater from a debug build: -Pocto.updates.force=true,
        // and -Pocto.updates.api=<address> for a pretend GitHub.
        buildConfigField("boolean", "UPDATES_FORCED", (project.findProperty("octo.updates.force") == "true").toString())
        buildConfigField("String", "UPDATES_API", "\"${(project.findProperty("octo.updates.api") as String?).orEmpty()}\"")
    }

    // The release key, only where the release workflow provides it (from
    // the repository's secrets): the keystore file and its passwords. Every
    // release must be signed with this same key, or phones refuse the
    // update; without it the release build is left unsigned.
    val releaseKeystore = providers.environmentVariable("OCTO_ANDROID_KEYSTORE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("OCTO_ANDROID_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("OCTO_ANDROID_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("OCTO_ANDROID_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        // Debug installs beside the release build instead of over it.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // A call to an Android API newer than the phone running it stops the app
    // there, so one anywhere fails the release build (lintVitalRelease runs
    // with every assembleRelease).
    lint {
        fatal += "NewApi"
    }
}

// Room writes each database version's schema here, so migrations can be checked.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Tests build old databases from these schemas and read the new one's SQL.
tasks.withType<Test>().configureEach {
    systemProperty("octo.schemas", file("schemas/app.winters.octo.catalog.CatalogDatabase").absolutePath)
    // The desktop JVM's optimizing compiler (C2, JDK 17.0.19) crashes now
    // and then on Robolectric's rewritten classes, killing the test run, so
    // tests stop at the quick compiler. Phones compile with their own runtime.
    jvmArgs("-XX:TieredStopAtLevel=1")
}

// The heap proof: -PoctoHeapProof runs it with a small, fixed heap, the
// way a phone gives an app one. Without the switch it is skipped.
tasks.withType<Test>().configureEach {
    if (project.hasProperty("octoHeapProof")) {
        maxHeapSize = (project.findProperty("octoHeapProof") as String).ifEmpty { "256m" }
        systemProperty("octo.heapProof", "true")
        // The save bounds the bound section runs, comma-separated (all of
        // them without it).
        project.findProperty("octoSaveBounds")?.let { systemProperty("octo.saveBounds", it as String) }
        // The cases the bound section runs ("switch", "retag"; both without it).
        project.findProperty("octoSaveCases")?.let { systemProperty("octo.saveCases", it as String) }
        // A smaller library for a dry run of the save sections.
        project.findProperty("octoProofSongs")?.let { systemProperty("octo.proofSongs", it as String) }
        // Long titles, album and artist names in the proof library.
        if (project.hasProperty("octoProofLongTitles")) systemProperty("octo.proofLongTitles", "true")
        // Where the bound section keeps each case's libraries between runs.
        project.findProperty("octoSaveFixtures")?.let { systemProperty("octo.saveFixtures", file(it as String).absolutePath) }
    }
    if (project.hasProperty("octoProofServer")) {
        systemProperty("octo.proofServer", (project.findProperty("octoProofServer") as String).ifEmpty { "true" })
        project.findProperty("octoProofDelay")?.let { systemProperty("octo.proofDelay", it as String) }
        // The proof library as it is after the server read its folders again.
        if (project.hasProperty("octoProofServerChange")) systemProperty("octo.proofServerChange", "true")
    }
    // The grep gate reads the app's own sources.
    systemProperty("octo.mainSources", file("src/main/java").absolutePath)
    // Each proof class gets a fresh JVM, so one's peak heap never counts in another's.
    if (project.hasProperty("octoHeapProof")) forkEvery = 1
}

dependencies {
    implementation(project(":design"))
    implementation(project(":subsonic"))
    implementation(project(":shared:core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.datastore.preferences)
    // Checking family requests now and then, even while the app is closed.
    implementation(libs.androidx.work.runtime)
    // The camera, for scanning a family QR code on the join screen. CameraX
    // is part of Android's own libraries (no Google Play services); the code
    // is read by ZXing, through the shared core.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.hilt.android)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.taglib)
    implementation(libs.androidx.palette)
    implementation(libs.reorderable)
    implementation(libs.media3.exoplayer)
    // Live radio stations that stream as HLS.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.inspector)
    // Casting: Android's list of outputs, and Google Cast (kept to the cast package).
    implementation(libs.androidx.mediarouter)
    implementation(libs.play.services.cast.framework)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Reads long server answers and backups as they stream in.
    implementation(libs.moshi)
    // Long lists shown a page at a time.
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    // Certificates made for a test's own TLS server and client.
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // The SQLite of the oldest phones Octo runs on, for checking queries there.
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.androidx.paging.testing)
    // Compose content and the player, run on Robolectric.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.mockwebserver)
    debugImplementation(libs.compose.ui.test.manifest)
}
