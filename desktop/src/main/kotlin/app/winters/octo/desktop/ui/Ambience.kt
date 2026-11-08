package app.winters.octo.desktop.ui

import app.winters.octo.desktop.system.OctopusArt
import app.winters.octo.design.motionScale
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.player.wash.ImmersiveWash
import app.winters.octo.desktop.player.wash.WashCover
import app.winters.octo.desktop.player.wash.WashCovers
import app.winters.octo.desktop.settings.AmbienceMotion
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.desktop.settings.Appearance
import app.winters.octo.desktop.settings.WashPrefs
import app.winters.octo.design.LocalOctoSurfaces
import app.winters.octo.design.LocalReduceMotion
import app.winters.octo.design.OctoColors
import app.winters.octo.player.immersive.CoverFadeMs
import app.winters.octo.player.immersive.FinalGain
import app.winters.octo.player.immersive.FpsChoices
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.player.immersive.overlay
import app.winters.octo.player.immersive.paceBpm
import app.winters.octo.player.immersive.readableAlpha
import app.winters.octo.theme.ResolvedPalette
import app.winters.octo.theme.ThemeChoice
import app.winters.octo.theme.contrastOn
import app.winters.octo.theme.glowMesh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.winters.octo.covers.toLch
import app.winters.octo.player.immersive.relativeLuminance
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.roundToInt

// Octo's own color, the violet of its icon: the key color while no song
// gives one.
val OctoKey = Color(0xFF9061FA)

// The color the glass takes a hint of: the playing song's, or Octo's.
val LocalKeyColour = staticCompositionLocalOf { OctoKey }

// A color made fit to tint with: the same hue, never too dark or gray to
// show on dark glass.
fun keyColour(argb: Int): Color {
    val hsv = FloatArray(3)
    java.awt.Color.RGBtoHSB(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF, hsv)
    // A cover with hardly any color gives Octo's own.
    if (hsv[1] < 0.12f) return OctoKey
    val rgb = java.awt.Color.HSBtoRGB(hsv[0], hsv[1].coerceIn(0.35f, 0.8f), hsv[2].coerceIn(0.75f, 1f))
    return Color(rgb or (0xFF shl 24))
}

// The glass's rim with a fifth of the key color in it, mixed from white on
// a dark theme and from the edge color on a light one.
@Composable
@ReadOnlyComposable
fun keyRim(key: Color, alpha: Float = 0.12f): Color {
    val s = LocalOctoSurfaces.current
    return lerp(if (s.light) s.edge else Color.White, key, 0.2f).copy(alpha = alpha)
}

// The colors of a page's words as ARGB, and the page under them: main,
// secondary and muted words, and the accent, complement and third.
@Immutable
data class PageWords(val page: Int, val light: Boolean, val text: Int, val secondary: Int, val muted: Int, val accents: List<Int>) {
    // Whether every word reads over `under`: main and secondary words at
    // 7:1, muted words and the accents at 4.5:1.
    fun readOver(under: Int): Boolean =
        contrastOn(text, under) >= SecondaryContrast && contrastOn(secondary, under) >= SecondaryContrast &&
            contrastOn(muted, under) >= 4.5 && accents.all { contrastOn(it, under) >= 4.5 }

    // The page color laid over a wash on a light theme, so every word reads
    // over each of its `patches` (its darkest, and its brightest, which a
    // mix in the screen's colors can leave darker still) shown at
    // `opacity`: from 0.50 up in 0.01 steps until they do. None on a dark
    // theme.
    fun veil(patches: List<Int>, opacity: Float): Float {
        if (!light) return 0f
        val washes = patches.map { overlay(it, opacity, page) }
        for (k in 50..100) {
            val veil = k / 100f
            if (washes.all { readOver(overlay(page, veil, it)) }) return veil
        }
        return 1f
    }
}

// The words of a page in `p`.
fun pageWords(p: ResolvedPalette) = PageWords(p.page, p.light, p.text, p.secondary, p.muted, listOf(p.accents.accent, p.complement, p.third))

// The words of the page in the theme being drawn.
@Composable
@ReadOnlyComposable
fun currentPageWords(): PageWords {
    val s = LocalOctoSurfaces.current
    return PageWords(
        s.page.toArgb(), s.light, s.text.toArgb(), s.secondary.toArgb(), s.muted.toArgb(),
        listOf(OctoColors.Accent.toArgb(), s.complement.toArgb(), s.third.toArgb()),
    )
}

// The darkest a prepared cover's wash gets as drawn: its darkest patch,
// dimmed as the final pass dims it (never brightened, which only helps).
val WashCover.darkest: Int get() = range.scaled(minOf(FinalGain, 1f)).low

// The patches a light theme's veil is measured over: for the wash, its
// darkest and its brightest as drawn; for the glow, its darkest and the
// cover's own brightest.
val WashCover.washPatches: List<Int> get() = listOf(darkest, peak)
val WashCover.glowPatches: List<Int> get() = listOf(darkest, glowPeak)

// The theme's own colors as a slow wash, for a theme that has them: Octo's
// icon (drawn from the vector at ART_PX) recolored into the theme's three
// glow colors (accent, complement and third), each kept readable at `alpha`
// over the page, where they blend too (glowMesh). The icon's darkest parts
// take the darkest of the three, its middle tones the middle one and its
// brightest the brightest, blended evenly between. Prepared with the wash's
// own tuning, as Octo's icon is, once for each set of colors and tuning.
internal object ThemeArt {
    private const val ART_PX = 512
    @Volatile private var made: WashCover? = null

    // The icon's pixels as levels from 0 (its darkest) to 1 (its
    // brightest), with each pixel's alpha, read once for the whole run.
    private class Levels(val levels: FloatArray, val alphas: ByteArray)
    @Volatile private var icon: Levels? = null

    // The three glow colors, readable at `alpha` (with `accent` too), from
    // darkest to brightest.
    fun trio(theme: ThemeChoice, glow: List<Int>, alpha: Float, accent: Int? = null): List<Int> {
        val perMille = kotlin.math.floor(alpha * 1000.0 + 0.5).toInt()
        return glowMesh(theme, listOf(glow[0], glow[1], glow[2]), perMille, accent).sortedBy { toLch(it).l }
    }

    @Synchronized
    fun cover(theme: ThemeChoice, glow: List<Int>, alpha: Float, tuning: WashTuning, accent: Int? = null): WashCover {
        val trio = trio(theme, glow, alpha, accent)
        val key = "theme|${theme.name}|" + trio.joinToString("|") { "%08X".format(it) } +
            "|${tuning.contrast}|${tuning.saturation}|${tuning.brightnessCap}"
        made?.takeIf { it.key == key }?.let { return it }
        val image = runCatching { picture(stops(theme, trio)) }.getOrNull()
        return try {
            WashCovers.prepared(key, image, tuning).also { next ->
                // Whoever draws the one before draws from a hold of their own.
                made?.retire()
                made = next
            }
        } finally {
            image?.close()
        }
    }

    private fun levels(): Levels = icon ?: run {
        val info = ImageInfo(ART_PX, ART_PX, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
        val bytes = OctopusArt.icon(ART_PX, shape = null).use { art ->
            val bitmap = Bitmap()
            try {
                bitmap.allocPixels(info)
                art.readPixels(bitmap, 0, 0)
                bitmap.readPixels(info, ART_PX * 4, 0, 0) ?: ByteArray(ART_PX * ART_PX * 4)
            } finally {
                bitmap.close()
            }
        }
        val count = ART_PX * ART_PX
        val luminance = DoubleArray(count)
        val alphas = ByteArray(count)
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        for (i in 0 until count) {
            val o = i * 4
            // BGRA in memory.
            val argb = (0xFF shl 24) or ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o].toInt() and 0xFF)
            alphas[i] = bytes[o + 3]
            luminance[i] = relativeLuminance(argb)
            if (bytes[o + 3].toInt() and 0xFF > 0) {
                low = minOf(low, luminance[i])
                high = maxOf(high, luminance[i])
            }
        }
        val span = (high - low).coerceAtLeast(1e-9)
        Levels(FloatArray(count) { ((luminance[it] - low) / span).coerceIn(0.0, 1.0).toFloat() }, alphas).also { icon = it }
    }

    // How far a dark theme's darkest and brightest stops sit from the middle
    // color, as a share of the way to its darkest and brightest colors, so
    // its glow has the depth Octo's own has. A light theme uses its colors
    // as they are.
    private const val DARK_DEPTH = 0.5f

    // The three stops the icon is drawn in for `theme`.
    private fun stops(theme: ThemeChoice, trio: List<Int>): List<Int> {
        if (theme.light) return trio
        val middle = Color(trio[1])
        return listOf(lerp(middle, Color(trio[0]), DARK_DEPTH).toArgb(), trio[1], lerp(middle, Color(trio[2]), DARK_DEPTH).toArgb())
    }

    // The icon in `trio` (darkest, middle, brightest), each pixel keeping
    // its own alpha.
    private fun picture(trio: List<Int>): Image {
        val art = levels()
        val bytes = ByteArray(ART_PX * ART_PX * 4)
        for (i in art.levels.indices) {
            val t = art.levels[i]
            val (from, to, at) = if (t <= 0.5f) Triple(trio[0], trio[1], t * 2f) else Triple(trio[1], trio[2], (t - 0.5f) * 2f)
            fun channel(shift: Int): Byte {
                val a = (from shr shift and 0xFF).toFloat()
                val b = (to shr shift and 0xFF).toFloat()
                return (a + (b - a) * at).roundToInt().coerceIn(0, 255).toByte()
            }
            val o = i * 4
            // BGRA in memory.
            bytes[o] = channel(0)
            bytes[o + 1] = channel(8)
            bytes[o + 2] = channel(16)
            bytes[o + 3] = art.alphas[i]
        }
        return Image.makeRaster(ImageInfo(ART_PX, ART_PX, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), bytes, ART_PX * 4)
    }
}

// What the window shows behind everything.
enum class WindowGlow { None, SignIn, Immersive, Quiet, Cover }

// What the window shows behind everything for `look`: Octo's own moving
// colors behind the sign-in; nothing with the glow off; the immersive
// wash; the stand-in colors while nothing with a cover plays; or the
// cover's glow.
fun windowGlow(look: Appearance, signedIn: Boolean, cover: String?): WindowGlow = when {
    !signedIn -> WindowGlow.SignIn
    !look.ambientGlow -> WindowGlow.None
    look.ambience == AmbienceStyle.Immersive -> WindowGlow.Immersive
    cover == null -> WindowGlow.Quiet
    else -> WindowGlow.Cover
}

// What stands in while nothing with a cover plays: Octo's icon in the
// theme's own colors (`glow`, kept readable at `quietOpacity`, with the
// chosen `accent` too), or as it is for a theme with none.
internal fun quietCover(theme: ThemeChoice, glow: List<Int>, strength: Float, tuning: WashTuning, accent: Int? = null): WashCover =
    if (glow.size >= 3) ThemeArt.cover(theme, glow, quietOpacity(strength), tuning, accent) else OctoArt.cover(tuning)

// The veil over the glow of the cover `coverId` on a light theme, worked
// out from the same prepared copy the page's shade reads, as if black
// until it is ready; none on a dark theme.
@Composable
internal fun rememberGlowVeil(app: AppState, coverId: String?): Float {
    val words = currentPageWords()
    val settings by app.settings.state.collectAsState()
    val wash = settings.appearance.wash
    val tuning = WashTuning(wash.contrast, wash.saturation / 100f, wash.brightnessCap / 100f)
    val connection = app.connection
    val cover by produceState<WashCover?>(null, coverId, connection, tuning, words.light) {
        value = null
        if (words.light && coverId != null && connection != null) {
            app.washCovers.follow(connection.client, coverId, tuning).collect { value = it }
        }
    }
    val target = words.veil(cover?.glowPatches ?: listOf(0xFF000000.toInt()), glowOpacity(1f))
    val veil by animateFloatAsState(target, tween(motionScale().ms(CoverFadeMs.toInt())), label = "glow veil")
    return if (words.light) veil else 0f
}

// Octo's icon (drawn from the vector at OCTO_ART_PX) made ready for the
// wash, once for the whole run.
internal object OctoArt {
    private const val OCTO_ART_PX = 512
    @Volatile private var made: WashCover? = null

    fun cover(tuning: WashTuning): WashCover {
        made?.takeIf { it.key == keyFor(tuning) }?.let { return it }
        val image = runCatching {
            OctopusArt.icon(OCTO_ART_PX, shape = null)
        }.getOrNull()
        return WashCovers.prepared(keyFor(tuning), image, tuning).also { made = it }
    }

    private fun keyFor(tuning: WashTuning) = "octo|${tuning.contrast}|${tuning.saturation}|${tuning.brightnessCap}"
}

// Octo's own colors as a slow wash, for when there is no cover to show:
// behind the sign-in, and very dim behind the pages while nothing plays.
// It is the full player's background seeded with the app's icon, or in a
// theme with colors of its own, with those. With motion reduced, `moving`
// off, or the window minimized or in the tray, it holds still. `veil` is
// how much of the page color lies over it; a light theme lays more over
// that, as far as its words need.
@Composable
fun OctoAmbience(app: AppState, moving: Boolean, veil: Float, modifier: Modifier = Modifier) {
    val settings by app.settings.state.collectAsState()
    val look = settings.appearance
    val wash = look.wash
    val tuning = WashTuning(wash.contrast, wash.saturation / 100f, wash.brightnessCap / 100f)
    val theme = app.palette.collectAsState().value.theme
    val glow = LocalOctoSurfaces.current.glow.map { it.toArgb() }
    val accent = OctoColors.Accent.toArgb()
    val cover by produceState<WashCover?>(null, tuning, theme, glow, look.glowStrength, accent) {
        value = withContext(Dispatchers.Default) { quietCover(theme, glow, look.glowStrength, tuning, accent) }
    }
    val words = currentPageWords()
    val light = cover?.let { words.veil(it.washPatches, glowOpacity(1f)) } ?: 0f
    Box(modifier.fillMaxSize().background(OctoColors.Background)) {
        ImmersiveWash(
            cover,
            paceBpm(null, false),
            // Calm and cheap: a gentle pace at a modest frame rate.
            fpsLimit = minOf(wash.fps, 30),
            speed = 0.12f,
            moving = moving && !LocalReduceMotion.current && LocalWindowShown.current,
            dolly = { 1f },
        )
        Box(Modifier.fillMaxSize().alpha(veil).background(OctoColors.Background))
        if (light > 0f) Box(Modifier.fillMaxSize().alpha(light).background(OctoColors.Background))
    }
}

// The key color for a prepared cover.
fun WashCover.keyColour(): Color = if (main == 0) OctoKey else keyColour(main)

// Whether what reads it is on screen: the window not minimized and not
// hidden in the tray, and for the frame and its colors, not covered by
// the full player. What moves rests while it is not.
val LocalWindowShown = compositionLocalOf { true }

// Whether the window is drawn by the processor, without the graphics card
// (an old or virtual machine). A moving wash costs too much there, so it
// holds still.
val LocalSoftwareDrawing = compositionLocalOf { false }

// The most of the immersive wash that shows behind the pages, by the
// strength setting: from a third to nine tenths.
fun immersiveOpacity(strength: Float): Float = 0.3f + 0.6f * strength.coerceIn(0f, 1f)

// How much of Octo's own colors show while nothing plays: what the glow
// shows of them, a fifth to a half.
fun quietOpacity(strength: Float): Float = 0.2f + 0.3f * strength.coerceIn(0f, 1f)

// The contrast main and secondary words keep over what is behind them.
const val SecondaryContrast = 7.0

// The contrast the pages' quieter words (the accent color, used for
// subtitles and details) keep over the wash's brightest part, the usual
// minimum for reading. The quietest and the secondary words are brought
// up to theirs by the page's own shade (PageShade.kt), so the colors stay
// rich.
const val PageContrast = 4.5

// How much of this cover's wash shows behind the pages: as much as the
// strength asks, less when the cover is bright enough to wash out the
// words. The page's words stay light, so a bright cover shows fainter.
// `page` is the page color and `words` the quieter words' color.
fun WashCover.pageOpacity(strength: Float, page: Color, words: Color): Float =
    readableAlpha(peak, page.toArgb(), words.toArgb(), PageContrast, immersiveOpacity(strength))

// How much of the glow shows: a little more than a tenth up to a half, by
// the strength.
fun glowOpacity(strength: Float): Float = 0.12f + 0.38f * strength.coerceIn(0f, 1f)

// How fast the immersive colors drift behind the pages, as a share of the
// full pace, or null when they hold still. Gentle is half the full
// player's own speed; Full is its speed.
fun ambienceSpeed(motion: AmbienceMotion, wash: WashPrefs): Float? = when (motion) {
    AmbienceMotion.Still -> null
    AmbienceMotion.Gentle -> wash.speed / 200f
    AmbienceMotion.Full -> wash.speed / 100f
}

// Whether the immersive colors move now: only while music plays, the
// window is on screen and the full player (which has its own) is shut;
// never when held still, with motion reduced, or without a graphics card.
fun ambienceMoves(motion: AmbienceMotion, playing: Boolean, shown: Boolean, software: Boolean, fullPlayer: Boolean, reduced: Boolean): Boolean =
    motion != AmbienceMotion.Still && playing && shown && !software && !fullPlayer && !reduced

// The frame rate behind the pages: the lowest of the phone's choices, 30,
// or the full player's own when that is lower. The frame's glass blurs
// this layer again every frame, and at a drift this slow more frames show
// nothing new.
fun ambienceFps(wash: WashPrefs): Int = minOf(wash.fps, FpsChoices.first())

// The full player's moving wash behind the whole window: the playing
// song's cover, as the full player draws it and from the same prepared
// copy, dimmed behind the pages so their words stay readable. With nothing
// playing, Octo's own colors stand in, still. It moves only while music
// plays, the window is on screen and the full player is shut (it has its
// own), and never with motion reduced or without a graphics card.
@Composable
fun ImmersiveAmbience(app: AppState, modifier: Modifier = Modifier) {
    val settings by app.settings.state.collectAsState()
    val look = settings.appearance
    val wash = look.wash
    val tuning = WashTuning(wash.contrast, wash.saturation / 100f, wash.brightnessCap / 100f)
    val state by app.player.state.collectAsState()
    val song = state.current?.song
    val connection = app.connection
    val theme = app.palette.collectAsState().value.theme
    val glow = LocalOctoSurfaces.current.glow.map { it.toArgb() }
    val accent = OctoColors.Accent.toArgb()
    val cover by produceState<WashCover?>(null, song?.coverArt, song == null, connection, tuning, theme, glow, look.glowStrength, accent) {
        if (song == null || connection == null) {
            value = withContext(Dispatchers.Default) { quietCover(theme, glow, look.glowStrength, tuning, accent) }
        } else {
            app.washCovers.follow(connection.client, song.coverArt, tuning).collect { value = it }
        }
    }
    val moving = ambienceMoves(
        look.ambienceMotion,
        playing = song != null && state.playing,
        shown = LocalWindowShown.current,
        software = LocalSoftwareDrawing.current,
        fullPlayer = app.fullPlayer,
        reduced = LocalReduceMotion.current,
    )
    ImmersiveBackdrop(cover, look.glowStrength, paceBpm(song?.bpm, wash.useBpm), ambienceFps(wash), ambienceSpeed(look.ambienceMotion, wash) ?: 0f, moving, modifier, quiet = song == null)
}

// The wash behind the pages over the page color, as much of it showing as
// the strength asks and the cover allows (`pageOpacity`), easing between
// covers. `quiet` (nothing playing, the stand-in colors) keeps it as dim
// as the glow keeps them. On a light theme a veil of the page color lies
// over it, as far as the words need over its darkest patch at full
// strength.
@Composable
internal fun ImmersiveBackdrop(
    cover: WashCover?,
    strength: Float,
    bpm: Float,
    fps: Int,
    speed: Float,
    moving: Boolean,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
) {
    val page = OctoColors.Background
    val most = cover?.pageOpacity(strength, page, OctoColors.TextSecondary) ?: 0f
    val target = if (quiet) minOf(most, quietOpacity(strength)) else most
    val opacity by animateFloatAsState(target, tween(motionScale().ms(CoverFadeMs.toInt())), label = "ambience")
    val words = currentPageWords()
    val veil by animateFloatAsState(cover?.let { words.veil(it.washPatches, immersiveOpacity(1f)) } ?: 0f, tween(motionScale().ms(CoverFadeMs.toInt())), label = "ambience veil")
    Box(modifier.fillMaxSize().background(page)) {
        ImmersiveWash(cover, bpm, fps, speed, moving, dolly = { 1f }, opacity = { opacity })
        if (words.light) Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil }.background(page))
    }
}
