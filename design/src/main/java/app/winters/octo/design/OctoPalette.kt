package app.winters.octo.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.winters.octo.theme.AccentChoice
import app.winters.octo.theme.AccentFamily
import app.winters.octo.theme.ResolvedPalette
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.resolvePalette

// The surface and word colors one theme draws. Equal values compare equal,
// so providing a new copy with the same colors redraws nothing.
@Immutable
data class OctoSurfaces(
    val light: Boolean,
    val page: Color,
    val card: Color,
    val raised: Color,
    val sheet: Color,
    val edge: Color,
    val backgroundSecondary: Color,
    val text: Color,
    val secondary: Color,
    val muted: Color,
    val ink: Color,
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val inkQuaternary: Color,
    val inkQuinary: Color,
    val offTrack: Color,
    val complement: Color,
    val third: Color,
    val glow: List<Color>,
    // How strong shadows are, 1 on a dark theme.
    val shadow: Float,
    // Words in the accent read as this instead, inside a selected row.
    val accentWords: Color? = null,
)

fun surfacesOf(p: ResolvedPalette) = OctoSurfaces(
    light = p.light, page = Color(p.page), card = Color(p.card), raised = Color(p.raised), sheet = Color(p.sheet),
    edge = Color(p.edge), backgroundSecondary = Color(p.backgroundSecondary), text = Color(p.text),
    secondary = Color(p.secondary), muted = Color(p.muted), ink = Color(p.ink), inkPrimary = Color(p.inkPrimary),
    inkSecondary = Color(p.inkSecondary), inkTertiary = Color(p.inkTertiary), inkQuaternary = Color(p.inkQuaternary),
    inkQuinary = Color(p.inkQuinary), offTrack = Color(p.offTrack), complement = Color(p.complement),
    third = Color(p.third), glow = p.glow.map(::Color), shadow = p.shadow / 1000f,
)

// The accent's colors, each its own state, so a new accent redraws only
// what reads it, and drawing code may read it while drawing.
@Stable
class OctoAccentFamily(initial: AccentFamily) {
    var accent by mutableStateOf(Color(initial.accent)); private set
    var hover by mutableStateOf(Color(initial.hover)); private set
    var tonal by mutableStateOf(Color(initial.tonal)); private set
    var selected by mutableStateOf(Color(initial.selected)); private set
    var selectedWords by mutableStateOf(Color(initial.selectedWords)); private set
    var menuPress by mutableStateOf(Color(initial.menuPress)); private set
    var fill by mutableStateOf(Color(initial.fill)); private set
    var onFill by mutableStateOf(Color(initial.onFill)); private set
    var onAccent by mutableStateOf(Color(initial.onAccent)); private set
    var focusRing by mutableStateOf(Color(initial.focusRing)); private set
    var glassAccent by mutableStateOf(Color(initial.glassAccent)); private set

    // Takes `family`'s colors. Only a change redraws anything.
    fun use(family: AccentFamily) {
        accent = Color(family.accent)
        hover = Color(family.hover)
        tonal = Color(family.tonal)
        selected = Color(family.selected)
        selectedWords = Color(family.selectedWords)
        menuPress = Color(family.menuPress)
        fill = Color(family.fill)
        onFill = Color(family.onFill)
        onAccent = Color(family.onAccent)
        focusRing = Color(family.focusRing)
        glassAccent = Color(family.glassAccent)
    }
}

// Octo's own palette, the default before anything is read and in previews.
val OctoDefaultPalette: ResolvedPalette = resolvePalette(ThemeChoice.Octo, AccentChoice.Octo, null, false)

val LocalOctoSurfaces = compositionLocalOf { surfacesOf(OctoDefaultPalette) }
val LocalOctoAccent = staticCompositionLocalOf { OctoAccentFamily(OctoDefaultPalette.accents) }

// What OverArt provides: Octo's dark surfaces and the accent's dark form.
private val LocalOverArtSurfaces = staticCompositionLocalOf { surfacesOf(OctoDefaultPalette) }
private val LocalOverArtAccent = staticCompositionLocalOf { OctoAccentFamily(OctoDefaultPalette.overArt) }

internal val OctoDarkSurfaces = surfacesOf(OctoDefaultPalette)

// Whether what is drawn here sits over art, in Octo's dark look: true inside
// an enabled OverArt and everything it holds.
val LocalOverArtOn = staticCompositionLocalOf { false }

// What wraps every themed subtree: Android's Material theme on the phone,
// nothing on the desktop, which draws no Material parts.
val LocalThemeWrapper = staticCompositionLocalOf<@Composable (@Composable () -> Unit) -> Unit> { { it() } }

// Draws everything inside in `palette`. Placed once at each window's root.
@Composable
fun ProvideOctoTheme(palette: ResolvedPalette, content: @Composable () -> Unit) {
    val surfaces = surfacesOf(palette)
    val themeAccent = remember { OctoAccentFamily(palette.accents) }
    val overArtAccent = remember { OctoAccentFamily(palette.overArt) }
    themeAccent.use(palette.accents)
    overArtAccent.use(palette.overArt)
    CompositionLocalProvider(
        LocalOctoSurfaces provides surfaces,
        LocalOctoAccent provides themeAccent,
        LocalOverArtSurfaces provides OctoDarkSurfaces,
        LocalOverArtAccent provides overArtAccent,
    ) {
        LocalThemeWrapper.current(content)
    }
}

// Draws everything inside in Octo's dark look with the accent's dark form,
// for what sits over a cover or the full player. `enabled` false draws in
// the theme, and the structure stays the same either way.
@Composable
fun OverArt(enabled: Boolean = true, content: @Composable () -> Unit) {
    val surfaces = if (enabled) LocalOverArtSurfaces.current else LocalOctoSurfaces.current
    val accent = if (enabled) LocalOverArtAccent.current else LocalOctoAccent.current
    val on = enabled || LocalOverArtOn.current
    CompositionLocalProvider(LocalOctoSurfaces provides surfaces, LocalOctoAccent provides accent, LocalOverArtOn provides on) {
        LocalThemeWrapper.current(content)
    }
}

// Inside a selected row's fill: secondary, muted and accent words read in
// the color solved for that fill. `selected` false leaves the words as they
// are, and the structure stays the same either way.
@Composable
fun SelectedFill(selected: Boolean = true, content: @Composable () -> Unit) {
    val words = LocalOctoAccent.current.selectedWords
    val current = LocalOctoSurfaces.current
    val surfaces = if (selected) current.copy(secondary = words, muted = words, inkSecondary = words, accentWords = words) else current
    CompositionLocalProvider(LocalOctoSurfaces provides surfaces, content = content)
}

// What a sheet or menu holds, as it was when it last opened.
private class Held<T>(var open: Boolean, var value: T)

// `value` as it was when `open` last became true, kept while it stays open
// and all through its closing, so what changes behind a sheet or menu (the
// player closing as it goes) changes nothing in it.
@Composable
fun <T> rememberHeld(open: Boolean, value: T): T {
    val held = remember { Held(open, value) }
    if (open && !held.open) held.value = value
    held.open = open
    return held.value
}

// Draws a sheet or menu over art or in the theme as it was when `open` last
// became true, kept through its closing, so the player closing behind it
// changes nothing in it. The colors themselves stay live: a theme switched
// while it is open draws in the new theme.
@Composable
fun HoldLook(open: Boolean, content: @Composable () -> Unit) {
    OverArt(enabled = rememberHeld(open, LocalOverArtOn.current), content = content)
}

// A dark film's color in this theme: as it is on a dark theme; on a light
// one the sheet color at half again its opacity, at most 0.92.
@Composable
@ReadOnlyComposable
fun glassFilm(darkAlpha: Float): Color {
    val s = LocalOctoSurfaces.current
    return if (s.light) s.sheet.copy(alpha = (0.5f + darkAlpha).coerceAtMost(0.92f)) else Color.Black.copy(alpha = darkAlpha)
}

// A white rim's color in this theme: as it is on a dark theme; the edge
// color at twice its opacity, at most 0.35, on a light one.
@Composable
@ReadOnlyComposable
fun glassRim(darkAlpha: Float): Color {
    val s = LocalOctoSurfaces.current
    return if (s.light) s.edge.copy(alpha = (darkAlpha * 2f).coerceAtMost(0.35f)) else Color.White.copy(alpha = darkAlpha)
}

// A shadow's color in this theme: black on a dark theme, the theme's own ink
// on a light one, at the theme's shadow strength.
@Composable
@ReadOnlyComposable
fun shadowColor(darkAlpha: Float): Color {
    val s = LocalOctoSurfaces.current
    return (if (s.light) s.ink else Color.Black).copy(alpha = darkAlpha * s.shadow)
}
