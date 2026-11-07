package app.winters.octo.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import app.winters.octo.design.Ambience
import app.winters.octo.design.LocalOctoSurfaces
import app.winters.octo.design.OctoColors
import app.winters.octo.design.motionScale
import app.winters.octo.desktop.AppState
import app.winters.octo.desktop.player.wash.WashCover
import app.winters.octo.desktop.settings.Appearance
import app.winters.octo.desktop.settings.AmbienceStyle
import app.winters.octo.player.immersive.CoverFadeMs
import app.winters.octo.player.immersive.WashTuning
import app.winters.octo.player.immersive.contrastRatio
import app.winters.octo.player.immersive.overlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A soft shade of black (of the page color on a light theme) behind the
// page's words, so the window's colors can stay rich while the quietest
// words (TextMuted: times, counts, small captions) and the secondary ones
// still read. It is worked out from the cover itself: a dark
// cover gets none, a bright one just enough. With the glow it follows the
// glow down the page and fades out where the glow does; with the immersive
// wash it lies evenly over the page. It is shade, not a panel: no edge of
// its own, and the glass of the frame and the player is left as it is.

// What the quietest words keep over the brightest color behind them:
// WCAG AA for small text, and a hair more for the grain and the blur.
const val ShadeContrast = 4.6

// What secondary words keep the same way: 7:1, and a hair more.
const val ShadeSecondaryContrast = 7.1

// The most shade a page gets, however bright the cover.
const val MaxShade = 0.85f

// The shade (`shade`, black on a dark theme, the page color on a light
// one) `under` (the riskiest color behind the words) needs over it for the
// quietest words (`muted`) to read, and with `secondary`, the secondary
// words at ShadeSecondaryContrast too: 0 when they already do.
fun shadeFor(under: Int, muted: Color, secondary: Color? = null, shade: Int = 0xFF000000.toInt()): Float {
    fun reads(amount: Float): Boolean {
        val back = overlay(shade, amount, under)
        val ink = muted.compositeOver(Color(back)).toArgb()
        val words = secondary?.compositeOver(Color(back))?.toArgb()
        return contrastRatio(ink, back) >= ShadeContrast && (words == null || contrastRatio(words, back) >= ShadeSecondaryContrast)
    }
    if (reads(0f)) return 0f
    if (!reads(MaxShade)) return MaxShade
    var low = 0f
    var high = MaxShade
    repeat(20) {
        val mid = (low + high) / 2f
        if (reads(mid)) high = mid else low = mid
    }
    return high
}

// The shade down the window, as stops: how far down (from the window's
// top) and how dark, in `color`. Past the last stop it stays as the last.
@Immutable
data class PageShade(val stops: List<Pair<Dp, Float>>, val color: Color = Color.Black) {
    val most: Float get() = stops.maxOfOrNull { it.second } ?: 0f

    companion object {
        val None = PageShade(emptyList())

        // The same shade all the way down.
        fun even(shade: Float, color: Color = Color.Black): PageShade =
            if (shade <= 0f) None else PageShade(listOf(Ambience.Height * 0f to shade, Ambience.Height to shade), color)
    }
}

// How many steps the glow's shade is worked out at down its height.
private const val GlowSteps = 8

// The shade for the glow: at each depth, what the glow's riskiest color
// (`peak`: the brightest of the cover as it is on a dark theme, its
// darkest patch on a light one) comes to there, faded as the glow fades
// into the page (`glow` at the top, none at its foot), under `veil` of
// the page color. `page` is the page color, `muted` the quietest words'
// and `secondary` the secondary words'; `shade` the shade's color.
fun glowShade(
    peak: Int,
    glow: Float,
    page: Color,
    muted: Color,
    secondary: Color? = null,
    veil: Float = 0f,
    shade: Color = Color.Black,
): PageShade {
    val base = page.toArgb()
    val stops = (0..GlowSteps).map { i ->
        val t = i / GlowSteps.toFloat()
        Ambience.Height * t to shadeFor(overlay(base, veil, overlay(peak, glow * (1f - t), base)), muted, secondary, shade.toArgb())
    }
    return if (stops.all { it.second <= 0f }) PageShade.None else PageShade(stops, shade)
}

// The shade for a wash lying evenly behind the page at `opacity`, under
// `veil` of the page color.
fun evenShade(
    peak: Int,
    opacity: Float,
    page: Color,
    muted: Color,
    secondary: Color? = null,
    veil: Float = 0f,
    shade: Color = Color.Black,
): PageShade {
    val base = page.toArgb()
    return PageShade.even(shadeFor(overlay(base, veil, overlay(peak, opacity, base)), muted, secondary, shade.toArgb()), shade)
}

// The shade for the window's colors as they are now: the ambience's
// style and strength and the cover it shows (`cover`, or the stand-in
// colors when nothing plays), for the page's `words`. On a dark theme it
// is black, measured over the brightest color; on a light one it is the
// page color, measured over the darkest patch under the veil the wash
// gets there.
fun shadeFor(look: Appearance, cover: WashCover?, playing: Boolean, words: PageWords): PageShade {
    if (!look.ambientGlow || cover == null) return PageShade.None
    val page = Color(words.page)
    val muted = Color(words.muted)
    val secondary = Color(words.secondary)
    val shade = if (words.light) page else Color.Black
    return when {
        look.ambience == AmbienceStyle.Immersive -> {
            val most = cover.pageOpacity(look.glowStrength, page, secondary)
            val opacity = if (playing) most else minOf(most, quietOpacity(look.glowStrength))
            if (words.light) {
                evenShade(cover.darkest, opacity, page, muted, secondary, words.veil(cover.washPatches, immersiveOpacity(1f)), shade)
            } else {
                evenShade(cover.peak, opacity, page, muted, secondary)
            }
        }
        playing && words.light -> glowShade(cover.darkest, glowOpacity(look.glowStrength), page, muted, secondary, words.veil(cover.glowPatches, glowOpacity(1f)), shade)
        playing -> glowShade(cover.glowPeak, glowOpacity(look.glowStrength), page, muted, secondary)
        words.light -> evenShade(cover.darkest, quietOpacity(look.glowStrength), page, muted, secondary, words.veil(cover.washPatches, glowOpacity(1f)), shade)
        else -> evenShade(cover.peak, quietOpacity(look.glowStrength), page, muted, secondary)
    }
}

// The shade for what the window shows now, easing as covers change.
@Composable
fun rememberPageShade(app: AppState): PageShade {
    val settings by app.settings.state.collectAsState()
    val look = settings.appearance
    val wash = look.wash
    val tuning = WashTuning(wash.contrast, wash.saturation / 100f, wash.brightnessCap / 100f)
    val state by app.player.state.collectAsState()
    val coverId = state.current?.song?.coverArt
    val playing = state.current != null && (coverId != null || look.ambience == AmbienceStyle.Immersive)
    val connection = app.connection
    val theme = app.palette.collectAsState().value.theme
    val glow = LocalOctoSurfaces.current.glow.map { it.toArgb() }
    val accent = OctoColors.Accent.toArgb()
    // The same prepared copy the ambience draws from (kept, so no extra work).
    val cover by produceState<WashCover?>(null, coverId, playing, connection, tuning, theme, glow, look.glowStrength, accent) {
        if (!playing || connection == null) {
            value = withContext(Dispatchers.Default) { quietCover(theme, glow, look.glowStrength, tuning, accent) }
        } else {
            app.washCovers.follow(connection.client, coverId, tuning).collect { value = it }
        }
    }
    val words = currentPageWords()
    val target = shadeFor(look, cover, playing, words)
    val color = if (words.light) OctoColors.Background else Color.Black
    // The shape follows at once; how dark it is eases like the colors do.
    val most by animateFloatAsState(target.most, tween(motionScale().ms(CoverFadeMs.toInt())), label = "page shade")
    val top = target.most
    if (top <= 0f) return if (most <= 0f) PageShade.None else PageShade.even(most, color)
    return PageShade(target.stops.map { (at, shade) -> at to shade * (most / top) }, color)
}

// Draws the shade behind what follows, for a page whose top is `top` below
// the window's.
fun Modifier.pageShade(shade: PageShade, top: Dp): Modifier = drawBehind {
    if (shade.most <= 0f) return@drawBehind
    val start = -top.toPx()
    val end = start + (shade.stops.last().first.toPx().coerceAtLeast(1f))
    val span = end - start
    val stops = shade.stops.map { (at, s) -> ((at.toPx() - 0f) / span).coerceIn(0f, 1f) to shade.color.copy(alpha = s) }.toTypedArray()
    drawRect(Brush.verticalGradient(colorStops = stops, startY = start, endY = end))
}
