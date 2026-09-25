package app.winters.octo.design

import androidx.compose.ui.graphics.Color

// Winters' Glass, the palette the other apps share. Dark only.
object OctoColors {
    val Background = Color(0xFF0C0C0D)
    // A low-alpha wash that tints glass panels.
    val BackgroundSecondary = Color.White.copy(alpha = 0.03f)
    // The solid surface under cards and glazed controls.
    val BackgroundTertiary = Color(0xFF1A1A1B)
    val Accent = Color(0xFF97B1B9)
    val AccentHover = Color(0xFFADC4CC)
    val TextPrimary = Color.White
    val TextSecondary = Accent
    val TextMuted = Accent.copy(alpha = 0.6f)
    val Error = Color(0xFFEF4444)
}
