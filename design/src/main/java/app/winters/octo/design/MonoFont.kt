package app.winters.octo.design

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// JetBrains Mono (OFL, design/licenses/JetBrainsMono-OFL.txt), bundled so
// codes, passwords and addresses are fixed-width on every phone, whatever
// the phone's own monospace is.
val MonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)
