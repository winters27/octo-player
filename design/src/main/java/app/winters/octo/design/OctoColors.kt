package app.winters.octo.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.winters.octo.theme.errorWordsFor

// Mixes two colors channel by channel, the way a stylesheet does.
fun mix(from: Color, to: Color, amount: Float) = Color(
    red = from.red + (to.red - from.red) * amount,
    green = from.green + (to.green - from.green) * amount,
    blue = from.blue + (to.blue - from.blue) * amount,
)

// Octo's own accent, a blue gray: the default accent in Octo's theme, for
// code that names that color itself.
val OctoAccent = Color(0xFF97B1B9)

// Winters' Glass, the palette the other apps share. Every color here is the
// one the theme being drawn gives it.
object OctoColors {
    // Drawing code reads LocalOctoAccent.current's states, captured while composing.
    val Background: Color @Composable @ReadOnlyComposable get() = LocalOctoSurfaces.current.page

    // A low-alpha wash that tints glass panels.
    val BackgroundSecondary: Color @Composable @ReadOnlyComposable get() = LocalOctoSurfaces.current.backgroundSecondary

    // The solid surface under cards and glazed controls.
    val BackgroundTertiary: Color @Composable @ReadOnlyComposable get() = LocalOctoSurfaces.current.card

    // The line round a card or a panel, where the theme draws one.
    val Edge: Color @Composable @ReadOnlyComposable get() = LocalOctoSurfaces.current.edge

    // The accent chosen in Appearance: switches, sliders, rings and icons.
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.accent

    // Words drawn in the accent: the accent, or the color solved for the
    // selected row they sit in.
    val AccentWords: Color
        @Composable @ReadOnlyComposable
        get() = LocalOctoSurfaces.current.accentWords ?: LocalOctoAccent.current.accent

    // The color lit into the glass: the bar, glazed buttons, tabs and
    // sheets. The accent when the glass takes it, else the theme's own.
    val GlassAccent: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.glassAccent

    // A raised surface for rows to read and change, a clear step up from the page.
    val SurfaceRaised: Color @Composable @ReadOnlyComposable get() = LocalOctoSurfaces.current.raised
    val AccentHover: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.hover
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalOctoSurfaces.current.text

    // Words stay in the theme's quiet color whatever the accent, so a page
    // reads the same in every color.
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalOctoSurfaces.current.secondary

    // The quietest words, kept at WCAG AA's 4.5:1 on the page and the glass.
    val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalOctoSurfaces.current.muted

    // The theme's second and third colors: playing bars, stars, empty art.
    val Complement: Color @Composable @ReadOnlyComposable get() = LocalOctoSurfaces.current.complement
    val Third: Color @Composable @ReadOnlyComposable get() = LocalOctoSurfaces.current.third

    // The liked heart and the count pills: white in Octo, else the second color.
    val Highlight: Color
        @Composable @ReadOnlyComposable
        get() {
            val s = LocalOctoSurfaces.current
            return if (s.page == OctoDarkSurfaces.page && s.card == OctoDarkSurfaces.card) s.text else s.complement
        }

    // A filled star: the accent in Octo, else the second color.
    val Star: Color
        @Composable @ReadOnlyComposable
        get() {
            val s = LocalOctoSurfaces.current
            return if (s.page == OctoDarkSurfaces.page && s.card == OctoDarkSurfaces.card) LocalOctoAccent.current.accent else s.complement
        }

    // The switch's track when it is off.
    val OffTrack: Color @Composable @ReadOnlyComposable get() = LocalOctoSurfaces.current.offTrack

    val Error = Color(0xFFEF4444)

    // An error note's words and glyph: one red for light themes and one for
    // dark, each reading at 4.5:1 on every surface of its themes.
    val ErrorWords: Color @Composable @ReadOnlyComposable get() = Color(errorWordsFor(LocalOctoSurfaces.current.light))

    // The accent's family, each derived from the accent by the theme:

    // A standard button's resting fill.
    val AccentTonal: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.tonal

    // The quiet Optional tag after a field's name: the accent's words on the
    // accent's tonal fill.
    val TagWords: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.accent
    val TagFill: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.tonal

    // A selected list row, still the quieter pill, with the selected words on it.
    val AccentSelected: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.selected

    // Secondary words inside a selected row.
    val SelectedWords: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.selectedWords

    // The ink drawn on the plain accent.
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.onAccent

    // The ink drawn on a filled accent button.
    val OnAccentFill: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.onFill

    // The ring round a focused control.
    val FocusRing: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.focusRing

    // A menu row under the finger or the pointer: the selected row's tint,
    // half as strong, so it is quieter than the chosen one.
    val MenuPress: Color @Composable @ReadOnlyComposable get() = LocalOctoAccent.current.menuPress

    // A destructive action's words: a soft red. Its icon stays white.
    val Destructive = Color(0xFFFF3B30).copy(alpha = 0.85f)

    // The signal colors, for the rare thing that needs one.
    val SignalRed = Color(0xFFFF3B30)
    val SignalOrange = Color(0xFFFF9500)
    val SignalYellow = Color(0xFFFFCC00)
    val SignalGreen = Color(0xFF28CD41)
    val SignalBlue = Color(0xFF007AFF)
}
