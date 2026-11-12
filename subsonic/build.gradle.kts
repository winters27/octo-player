plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Built with Java 25 like the rest, but the Android app uses this module, so
// its classes stay at Java 17.
kotlin { jvmToolchain(25) }
tasks.withType<JavaCompile>().configureEach { options.release = 17 }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Streams the starred list without building the whole answer in memory.
    implementation(libs.moshi)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The gateway tests read the fixtures shared with the iPhone app.
tasks.withType<Test>().configureEach { inputs.dir(rootProject.file("fixtures/gateway")) }
