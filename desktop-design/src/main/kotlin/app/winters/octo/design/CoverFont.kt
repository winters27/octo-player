package app.winters.octo.design

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

// Inter Display, which playlist covers are set in: the three weights the
// design uses, read from the app's resources (octo-fonts/, shared from the
// phone's font resources).
val CoverFontFamily: FontFamily by lazy {
    fun font(name: String, weight: FontWeight) = Font(
        "octo-fonts/$name",
        CoverFontFamilyHolder::class.java.classLoader.getResourceAsStream("octo-fonts/$name.ttf")!!.use { it.readBytes() },
        weight,
    )
    FontFamily(
        font("inter_display_semibold", FontWeight.SemiBold),
        font("inter_display_light", FontWeight.Light),
        font("inter_display_regular", FontWeight.Normal),
    )
}

private object CoverFontFamilyHolder
