package app.winters.octo.design

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Inter Display, which playlist covers are set in: the four weights the
// design uses, from this module's font resources. The desktop reads the
// same files (desktop-design's CoverFont.kt).
val CoverFontFamily: FontFamily = FontFamily(
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_light, FontWeight.Light),
    Font(R.font.inter_display_regular, FontWeight.Normal),
    Font(R.font.inter_display_medium, FontWeight.Medium),
)
