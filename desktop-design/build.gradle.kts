// The desktop app's design system: the same tokens, glass and icons as the
// phone app, for Compose on the desktop JVM.
//
// The Android design module stays an Android library, untouched. Its files
// that use nothing but Compose and Haze (the colours, type, motion, the
// glaze, panels, the artwork rim, the switch and the pause glyph) are
// compiled here as they are, from the same source files, so the two apps
// cannot drift apart. The icons are the same Material Symbols vector files,
// read into ImageVectors at run time. What only makes sense on a desktop
// (menus at the pointer, hover, the slider, text fields) lives in this
// module's own sources.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

// The Android design files that compile unchanged on the desktop. A file
// added here must not import anything from Android; the build fails loudly
// if one does.
val sharedDesignFiles = listOf(
    "Artwork.kt",
    "Blur.kt",
    "GlassPanel.kt",
    "Glaze.kt",
    "GlazeInset.kt",
    "Glyphs.kt",
    "OctoColors.kt",
    "OctoMotion.kt",
    "OctoSwitch.kt",
    "OctoType.kt",
)

val shareDesign by tasks.registering(Sync::class) {
    from(rootProject.file("design/src/main/java/app/winters/octo/design")) { include(sharedDesignFiles) }
    into(layout.buildDirectory.dir("generated/sharedDesign/kotlin"))
}

// The phone app's icon vectors, packaged as resources under octo-icons/.
val shareIcons by tasks.registering(Sync::class) {
    from(rootProject.file("design/src/main/res/drawable")) {
        include("sym_*.xml")
        into("octo-icons")
    }
    into(layout.buildDirectory.dir("generated/sharedDesign/resources"))
}

sourceSets.main {
    kotlin.srcDir(shareDesign)
    resources.srcDir(shareIcons)
}

dependencies {
    api(compose.desktop.common)
    api(libs.haze)
    api(libs.haze.blur)
    testImplementation(libs.junit)
}
