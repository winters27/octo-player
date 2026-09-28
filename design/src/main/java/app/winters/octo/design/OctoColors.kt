package app.winters.octo.design

import androidx.compose.ui.graphics.Color

// Mixes two colours channel by channel, the way a stylesheet does.
fun mix(from: Color, to: Color, amount: Float) = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
)

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

    // The accent's family, each derived from the accent the same way:

    // A standard button's resting fill: a tenth of the accent in a dark grey.
    val AccentTonal = mix(Color(0xFF1E1E1E), Accent, 0.10f)

    // A selected list row: the accent 40% of the way out of black, so it is
    // still the darker pill, only lightly tinted, and white text reads on it.
    val AccentSelected = mix(Color.Black, Accent, 0.40f)

    // The ring round a focused control.
    val FocusRing = Accent.copy(alpha = 0.70f)

    // A menu row under the finger or the pointer: the selected row's tint,
    // half as strong, so it is quieter than the chosen one.
    val MenuPress = AccentSelected.copy(alpha = 0.55f)

    // A destructive action's words: a soft red. Its icon stays white.
    val Destructive = Color(0xFFFF3B30).copy(alpha = 0.85f)

    // The signal colours, for the rare thing that needs one.
    val SignalRed = Color(0xFFFF3B30)
    val SignalOrange = Color(0xFFFF9500)
    val SignalYellow = Color(0xFFFFCC00)
    val SignalGreen = Color(0xFF28CD41)
    val SignalBlue = Color(0xFF007AFF)
}
