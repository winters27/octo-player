package app.winters.octo.theme

import app.winters.octo.covers.lchToArgb
import app.winters.octo.covers.toLch
import app.winters.octo.player.immersive.contrastRatio
import kotlinx.serialization.Serializable

// The accent colour both apps draw selected states, sliders, switches and
// filled buttons in. Every fixed one is a light shade at the same OKLCH
// lightness as Octo's own (about 0.76), so each reads at WCAG AA or better
// as words on the page and as the fill under the page's dark ink. `argb`
// is null for the one taken from the playing song's cover.
@Serializable
enum class AccentChoice(val label: String, val argb: Int?) {
    Octo("Theme", 0xFF97B1B9.toInt()),
    Sky("Sky", 0xFF73BAEA.toInt()),
    Iris("Iris", 0xFFA3A9F6.toInt()),
    Lilac("Lilac", 0xFFC79DE6.toInt()),
    Rose("Rose", 0xFFE496B4.toInt()),
    Coral("Coral", 0xFFEF9687.toInt()),
    Amber("Amber", 0xFFDAA75D.toInt()),
    Lime("Lime", 0xFFA0BE66.toInt()),
    Mint("Mint", 0xFF6FC5A1.toInt()),
    FromArt("From the album art", null),
}

// The fixed accents, in the order the pickers show them.
val FixedAccents: List<AccentChoice> = AccentChoice.entries.filter { it.argb != null }

// Octo's own accent, the default.
val OctoAccentArgb: Int = AccentChoice.Octo.argb!!

// The page colour and the raised surface the accent sits on, and the dark
// ink drawn on an accent fill.
const val AccentPageArgb: Int = 0xFF0C0C0D.toInt()
const val AccentSurfaceArgb: Int = 0xFF1A1A1B.toInt()

// The contrast every accent keeps against the page, the raised surface
// and the dark ink: WCAG AA for small text.
const val AccentContrast = 4.5

// One line under the picker saying what the choice does in `theme`.
fun accentHelp(choice: AccentChoice, theme: ThemeChoice): String = when (choice) {
    AccentChoice.FromArt -> "Follows the playing song's cover. ${theme.label}'s own color while nothing plays or the cover is gray."
    AccentChoice.Octo -> "${theme.label}'s own color for selections, sliders, switches and buttons."
    else -> "Selections, sliders, switches and buttons in ${choice.label.lowercase()}."
}

// The same line in Octo's own theme.
fun accentHelp(choice: AccentChoice): String = accentHelp(choice, ThemeChoice.Octo)

fun accentFromArt(argb: Int): Int? = accentFromArt(argb, ThemeChoice.Octo)

// A cover's color made into an accent for `theme`: the same hue at the
// fixed accents' lightness, chroma held between theirs, lightened until it
// reads on a dark theme's page and card; on a light theme, that color's
// form for light surfaces.
fun accentFromArt(argb: Int, theme: ThemeChoice): Int? {
    val lch = toLch(argb)
    if (lch.c < MinArtChroma) return null
    val chroma = lch.c.coerceIn(0.07, 0.12)
    val darkTheme = if (theme.light) ThemeChoice.Octo else theme
    var accent = lchToArgb(0.76, chroma, lch.h)
    var k = 0
    while (!accentReads(accent, darkTheme) && k < 10) {
        k++
        accent = lchToArgb(0.76 + k * 0.02, chroma, lch.h)
    }
    return if (theme.light) onLightForm(accent, theme.base) else accent
}

// Below this chroma a cover is grey enough to keep Octo's own accent.
private const val MinArtChroma = 0.04

fun accentReads(argb: Int): Boolean = accentReads(argb, ThemeChoice.Octo)

// Whether an accent reads at AA on `theme`'s page and card.
fun accentReads(argb: Int, theme: ThemeChoice): Boolean =
    contrastRatio(argb, theme.base.page) >= AccentContrast && contrastRatio(argb, theme.base.card) >= AccentContrast

// The accent to draw now: a fixed one as it is, the cover's for From the
// album art (`art` is the playing cover's colour, null with none), or
// Octo's own when the cover gives none.
fun accentArgb(choice: AccentChoice, art: Int?): Int =
    choice.argb ?: art?.let(::accentFromArt) ?: OctoAccentArgb
