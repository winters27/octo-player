package app.winters.octo.theme

import app.winters.octo.covers.Lch
import app.winters.octo.covers.lchToArgb
import app.winters.octo.covers.toLch
import app.winters.octo.player.immersive.contrastRatio
import app.winters.octo.player.immersive.relativeLuminance

private const val White = 0xFFFFFFFF.toInt()
private const val Black = 0xFF000000.toInt()

private fun ch(argb: Int, shift: Int) = argb shr shift and 0xFF

// `from` moved `perMille` thousandths of the way to `to`, channel by
// channel, rounded half up. Opaque.
fun mixArgb(from: Int, to: Int, perMille: Int): Int {
    fun one(shift: Int): Int {
        val f = ch(from, shift)
        val t = ch(to, shift)
        return (f * 1000 + (t - f) * perMille + 500).floorDiv(1000)
    }
    return (0xFF shl 24) or (one(16) shl 16) or (one(8) shl 8) or one(0)
}

// `argb` at an opacity of `perMille` thousandths.
fun alphaArgb(argb: Int, perMille: Int): Int = ((255 * perMille + 500) / 1000 shl 24) or (argb and 0xFFFFFF)

// A see-through color laid over an opaque one.
fun overArgb(fg: Int, bg: Int): Int {
    val a = ch(fg, 24)
    fun one(shift: Int) = (ch(fg, shift) * a + ch(bg, shift) * (255 - a) + 127) / 255
    return (0xFF shl 24) or (one(16) shl 16) or (one(8) shl 8) or one(0)
}

// The contrast of a color, see-through or not, over the layer under it.
fun contrastOn(fg: Int, layer: Int): Double = contrastRatio(overArgb(fg, layer), layer)

// A theme's own colors before the accent is chosen. Null where Octo
// builds a color from its accent, as it always has.
class ThemeBase(
    val page: Int, val card: Int, val raised: Int?, val sheet: Int?, val sheetAlpha: Int,
    val edge: Int?, val text: Int, val secondary: Int, val muted: Int?,
    val accent: Int, val complement: Int, val third: Int, val glow: List<Int>,
    val ink: Int, val inkSecondary: Int, val inkTertiary: Int, val offTrack: Int, val shadow: Int,
)

// Opacities and amounts below are in thousandths.
val ThemeChoice.base: ThemeBase get() = when (this) {
    ThemeChoice.Octo -> ThemeBase(
        page = 0xFF0C0C0D.toInt(), card = 0xFF1A1A1B.toInt(), raised = null, sheet = null, sheetAlpha = 970,
        edge = null, text = White, secondary = 0xFF98B2BA.toInt(), muted = null,
        accent = OctoAccentArgb, complement = OctoAccentArgb, third = OctoAccentArgb, glow = emptyList(),
        ink = White, inkSecondary = 550, inkTertiary = 250, offTrack = 340, shadow = 1000,
    )
    ThemeChoice.Moss -> ThemeBase(
        page = 0xFF0E1512.toInt(), card = 0xFF17211B.toInt(), raised = 0xFF202D26.toInt(), sheet = 0xFF213029.toInt(), sheetAlpha = 1000,
        edge = 0xFF3A4D42.toInt(), text = 0xFFEDF2EA.toInt(), secondary = 0xFFB5C9AE.toInt(), muted = 0xFF97AC92.toInt(),
        accent = 0xFF9ACB86.toInt(), complement = 0xFFE2B062.toInt(), third = 0xFF7FB8A8.toInt(),
        glow = listOf(0xFF2E6A36.toInt(), 0xFF7A6020.toInt(), 0xFF0F2A26.toInt()),
        ink = 0xFFEDF2EA.toInt(), inkSecondary = 560, inkTertiary = 250, offTrack = 390, shadow = 1000,
    )
    ThemeChoice.Dusk -> ThemeBase(
        page = 0xFF120E18.toInt(), card = 0xFF1E1726.toInt(), raised = 0xFF292032.toInt(), sheet = 0xFF2F243B.toInt(), sheetAlpha = 1000,
        edge = 0xFF47395A.toInt(), text = 0xFFF4EEF8.toInt(), secondary = 0xFFC9B6DA.toInt(), muted = 0xFFAB9ABE.toInt(),
        accent = 0xFFE7A0C4.toInt(), complement = 0xFFF2BE85.toInt(), third = 0xFF98ADEB.toInt(),
        glow = listOf(0xFF74308A.toInt(), 0xFF9A5040.toInt(), 0xFF1A2A5C.toInt()),
        ink = 0xFFF4EEF8.toInt(), inkSecondary = 550, inkTertiary = 250, offTrack = 380, shadow = 1000,
    )
    ThemeChoice.Daylight -> ThemeBase(
        page = 0xFFE7ECEF.toInt(), card = White, raised = 0xFFF2F5F7.toInt(), sheet = White, sheetAlpha = 1000,
        edge = 0xFFC9D2D8.toInt(), text = 0xFF12181C.toInt(), secondary = 0xFF2F4A55.toInt(), muted = 0xFF506570.toInt(),
        accent = 0xFF2E6577.toInt(), complement = 0xFF9A4722.toInt(), third = 0xFF5F55A0.toInt(),
        glow = listOf(0xFFBFD9E6.toInt(), 0xFFF2D3C0.toInt(), 0xFFD8D3F0.toInt()),
        ink = 0xFF12181C.toInt(), inkSecondary = 610, inkTertiary = 250, offTrack = 470, shadow = 250,
    )
    ThemeChoice.Paper -> ThemeBase(
        page = 0xFFEBE2D2.toInt(), card = 0xFFFAF6EE.toInt(), raised = 0xFFF2EBDF.toInt(), sheet = 0xFFFFFBF4.toInt(), sheetAlpha = 1000,
        edge = 0xFFCDBEA4.toInt(), text = 0xFF2A1F14.toInt(), secondary = 0xFF55412B.toInt(), muted = 0xFF6A5742.toInt(),
        accent = 0xFF8F3F1D.toInt(), complement = 0xFF3C6646.toInt(), third = 0xFF6F540E.toInt(),
        glow = listOf(0xFFE8C9A0.toInt(), 0xFFC9D6B8.toInt(), 0xFFE7C1AE.toInt()),
        ink = 0xFF2A1F14.toInt(), inkSecondary = 650, inkTertiary = 250, offTrack = 500, shadow = 250,
    )
}

// The colors built from an accent, for one kind of surface.
data class AccentFamily(
    val accent: Int,
    val hover: Int,
    val tonal: Int,
    val selected: Int,
    val selectedWords: Int,
    val menuPress: Int,
    val fill: Int,
    val onFill: Int,
    val onAccent: Int,
    val focusRing: Int,
    val glassAccent: Int,
)

// Every color one theme draws, with the accent chosen.
data class ResolvedPalette(
    val theme: ThemeChoice,
    val light: Boolean,
    val page: Int,
    val card: Int,
    val raised: Int,
    val sheet: Int,
    val edge: Int,
    val backgroundSecondary: Int,
    val text: Int,
    val secondary: Int,
    val muted: Int,
    val inkPrimary: Int,
    val inkSecondary: Int,
    val inkTertiary: Int,
    val inkQuaternary: Int,
    val inkQuinary: Int,
    val ink: Int,
    val offTrack: Int,
    val complement: Int,
    val third: Int,
    val glow: List<Int>,
    val shadow: Int,
    // The accent family for this theme's own surfaces.
    val accents: AccentFamily,
    // The accent family for surfaces over a cover, on Octo's dark surfaces.
    val overArt: AccentFamily,
)

// An accent's form for light surfaces: its hue, held in chroma, darkened
// until it reads at 5:1 on every layer and white reads on it at 4.5:1.
fun onLightForm(argb: Int, base: ThemeBase): Int {
    val lch = toLch(argb)
    val chroma = lch.c.coerceIn(0.06, 0.14)
    val layers = layersOf(base)
    for (k in 0..72) {
        val candidate = lchToArgb(0.56 - k * 0.005, chroma, lch.h)
        if (layers.all { contrastRatio(candidate, it) >= 5.0 } && contrastRatio(White, candidate) >= 4.5) return candidate
    }
    return lchToArgb(0.20, chroma, lch.h)
}

// An accent's form for dark surfaces: the fixed accents as they are, any
// other color lifted to their lightness the way a cover's is.
fun onDarkForm(argb: Int): Int = if (FixedAccents.any { it.argb == argb }) argb else accentFromArt(argb) ?: OctoAccentArgb

private fun layersOf(base: ThemeBase): List<Int> {
    val raised = base.raised ?: mixArgb(base.card, OctoAccentArgb, 60)
    val sheet = base.sheet ?: base.page
    return listOf(base.page, base.card, raised, sheet)
}

// The accent chosen, in the form `theme` draws it. Null art seed means
// nothing with a cover is playing.
fun themedAccent(theme: ThemeChoice, choice: AccentChoice, art: Int?): Int {
    val base = theme.base
    return when {
        choice == AccentChoice.Octo -> base.accent
        choice == AccentChoice.FromArt -> {
            val fromArt = art?.let { accentFromArt(it, theme) } ?: return base.accent
            fromArt
        }
        theme.light -> onLightForm(choice.argb!!, base)
        else -> choice.argb!!
    }
}

// The accent family on a theme's layers.
private fun family(light: Boolean, accent: Int, glassAccent: Int, card: Int, page: Int, secondary: Int, ink: Int): AccentFamily {
    val selected = if (light) {
        var k = 14
        var s = mixArgb(card, accent, k * 10)
        while (k < 40 && (contrastRatio(s, page) < 1.12 || contrastRatio(s, card) < 1.12)) {
            k++
            s = mixArgb(card, accent, k * 10)
        }
        s
    } else {
        mixArgb(Black, accent, 400)
    }
    val selectedWords = if (contrastRatio(secondary, selected) >= 7.0) {
        secondary
    } else {
        (850..1000 step 10).map { alphaArgb(ink, it) }.firstOrNull { contrastOn(it, selected) >= 7.0 } ?: ink
    }
    val fill = if (light) accent else mixArgb(accent, Black, 450)
    return AccentFamily(
        accent = accent,
        hover = when {
            light -> mixArgb(accent, Black, 180)
            accent == OctoAccentArgb -> 0xFFADC4CC.toInt()
            else -> mixArgb(accent, White, 220)
        },
        tonal = if (light) mixArgb(card, accent, 80) else mixArgb(0xFF1E1E1E.toInt(), accent, 100),
        selected = selected,
        selectedWords = selectedWords,
        menuPress = alphaArgb(selected, 550),
        fill = fill,
        onFill = if (light) White else contentArgbFor(fill),
        onAccent = if (light) White else page,
        focusRing = alphaArgb(accent, if (light) 1000 else 700),
        glassAccent = glassAccent,
    )
}

// White words on a dark fill, black on a light one, as `contentColorFor`
// decides: by CIE lightness, the way the eye judges it.
fun contentArgbFor(fill: Int): Int {
    val y = relativeLuminance(fill)
    val l = if (y > 216.0 / 24389.0) 116.0 * kotlin.math.cbrt(y) - 16.0 else y * 24389.0 / 27.0
    return if (l / 100.0 > 0.58) Black else White
}

// Every color `theme` draws with the accent `choice`, the playing cover's
// color `art` (null with none) and whether the glass takes the accent.
fun resolvePalette(theme: ThemeChoice, choice: AccentChoice, art: Int?, glassTint: Boolean): ResolvedPalette {
    val base = theme.base
    val accent = themedAccent(theme, choice, art)
    val glassAccent = if (glassTint) accent else base.accent
    val raised = base.raised ?: mixArgb(base.card, OctoAccentArgb, 60)
    val sheet = alphaArgb(mixArgb(base.sheet ?: base.page, glassAccent, 50), base.sheetAlpha)
    val muted = base.muted ?: alphaArgb(base.secondary, 800)
    val octo = ThemeChoice.Octo.base
    val darkAccent = if (theme.light) onDarkForm(accent) else accent
    return ResolvedPalette(
        theme = theme,
        light = theme.light,
        page = base.page,
        card = base.card,
        raised = raised,
        sheet = sheet,
        edge = base.edge ?: alphaArgb(base.ink, 100),
        backgroundSecondary = alphaArgb(base.ink, 30),
        text = base.text,
        secondary = base.secondary,
        muted = muted,
        inkPrimary = alphaArgb(base.ink, 850),
        inkSecondary = alphaArgb(base.ink, base.inkSecondary),
        inkTertiary = alphaArgb(base.ink, base.inkTertiary),
        inkQuaternary = alphaArgb(base.ink, 100),
        inkQuinary = alphaArgb(base.ink, 50),
        ink = base.ink,
        offTrack = alphaArgb(base.ink, base.offTrack),
        complement = base.complement,
        third = base.third,
        glow = base.glow,
        shadow = base.shadow,
        accents = family(theme.light, accent, glassAccent, base.card, base.page, base.secondary, base.ink),
        overArt = family(false, darkAccent, if (glassTint) darkAccent else OctoAccentArgb, octo.card, octo.page, octo.secondary, octo.ink),
    )
}

// Whether every word role on the page of `p` reads with `glow` at
// `alphaPerMille` over it: main and secondary words at 7:1, muted words,
// the theme's accent, complement and third, and `accent` when given, at
// 4.5:1.
private fun glowReads(p: ResolvedPalette, glow: Int, alphaPerMille: Int, accent: Int?): Boolean {
    val under = overArgb(alphaArgb(glow, alphaPerMille), p.page)
    return contrastRatio(p.text, under) >= 7.0 && contrastRatio(p.secondary, under) >= 7.0 &&
        contrastOn(p.muted, under) >= 4.5 &&
        (listOf(p.accents.accent, p.complement, p.third) + listOfNotNull(accent)).all { contrastRatio(it, under) >= 4.5 }
}

// A color's lightness moved `k` hundredths: down on a dark theme, up on a
// light one.
private fun stepped(lch: Lch, k: Int, light: Boolean): Int =
    lchToArgb((if (light) lch.l + k * 0.01 else lch.l - k * 0.01).coerceIn(0.0, 1.0), lch.c, lch.h)

// A cover's color for the page glow in `theme`, so every word role still
// reads with the glow at `alphaPerMille` over the page: on a dark theme the
// color darkened in steps (no change for a cover that already reads); on a
// light one mixed 70% toward the page, then lightened in steps. `accent` is
// the accent drawn now, when it is not the theme's own; it must read too.
fun glowForCover(theme: ThemeChoice, art: Int, alphaPerMille: Int, accent: Int? = null): Int {
    val p = resolvePalette(theme, AccentChoice.Octo, null, false)
    val start = if (theme.light) mixArgb(art, p.page, 700) else art
    val lch = toLch(start)
    for (k in 0..60) {
        val glow = if (k == 0) start else stepped(lch, k, theme.light)
        if (glowReads(p, glow, alphaPerMille, accent)) return glow
    }
    return p.page
}

// The colors of a glow mesh in `theme` (a cover's, or the theme's own at
// rest), each made readable by glowForCover, then, where the glow blends
// two of them, the even blend of every pair read too: while one does not,
// all of them darkened (a dark theme) or lightened (a light one) together
// in steps, so the mesh keeps its look.
fun glowMesh(theme: ThemeChoice, colors: List<Int>, alphaPerMille: Int, accent: Int? = null): List<Int> {
    val p = resolvePalette(theme, AccentChoice.Octo, null, false)
    val each = colors.map { glowForCover(theme, it, alphaPerMille, accent) }
    if (each.size < 2) return each
    val lch = each.map(::toLch)
    for (k in 0..100) {
        val mesh = if (k == 0) each else lch.map { stepped(it, k, theme.light) }
        val blends = mesh.indices.flatMap { i -> (i + 1 until mesh.size).map { j -> mixArgb(mesh[i], mesh[j], 500) } }
        if ((mesh + blends).all { glowReads(p, it, alphaPerMille, accent) }) return mesh
    }
    return each.map { p.page }
}

// The desktop key color (an icon tint taken from the cover) in `theme`:
// as it is on a dark page; on a light one, its hue darkened until it reads
// at 3:1 on every layer.
fun keyForTheme(theme: ThemeChoice, key: Int): Int {
    if (!theme.light) return key
    val base = theme.base
    val lch = toLch(key)
    val chroma = lch.c.coerceIn(0.06, 0.14)
    for (k in 0..72) {
        val candidate = lchToArgb(0.56 - k * 0.005, chroma, lch.h)
        if (layersOf(base).all { contrastRatio(candidate, it) >= 3.0 }) return candidate
    }
    return lchToArgb(0.20, chroma, lch.h)
}
