package app.winters.octo.design

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// JetBrains Mono (OFL), the phone's bundled font shared under octo-fonts/,
// so codes, passwords and addresses are fixed-width on every computer.
val MonoFontFamily: FontFamily by lazy {
    fun font(name: String, weight: FontWeight) = Font(
        "octo-fonts/$name",
        MonoFontHolder::class.java.classLoader.getResourceAsStream("octo-fonts/$name.ttf")!!.use { it.readBytes() },
        weight,
    )
    FontFamily(font("jetbrains_mono_regular", FontWeight.Normal), font("jetbrains_mono_semibold", FontWeight.SemiBold))
}

private object MonoFontHolder
