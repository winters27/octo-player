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

// A soft shade of black behind the page's words, so the window's colours
// can stay rich while the quietest words (TextMuted: times, counts, small
// captions) still read. It is worked out from the cover itself: a dark
// cover gets none, a bright one just enough. With the glow it follows the
// glow down the page and fades out where the glow does; with the immersive
// wash it lies evenly over the page. It is shade, not a panel: no edge of
// its own, and the glass of the frame and the player is left as it is.

// What the quietest words keep over the brightest colour behind them:
// WCAG AA for small text, and a hair more for the grain and the blur.
const val ShadeContrast = 4.6

// The most shade a page gets, however bright the cover.
const val MaxShade = 0.85f

// The black `under` (the brightest colour behind the words) needs over it
// for the quietest words to read: 0 when they already do.
fun shadeFor(under: Int): Float {
    fun reads(shade: Float): Boolean {
        val back = overlay(0xFF000000.toInt(), shade, under)
        val ink = OctoColors.TextMuted.compositeOver(Color(back)).toArgb()
        return contrastRatio(ink, back) >= ShadeContrast
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
// top) and how dark. Past the last stop it stays as the last.
@Immutable
data class PageShade(val stops: List<Pair<Dp, Float>>) {
    val most: Float get() = stops.maxOfOrNull { it.second } ?: 0f

    companion object {
        val None = PageShade(emptyList())

        // The same shade all the way down.
        fun even(shade: Float): PageShade = if (shade <= 0f) None else PageShade(listOf(Ambience.Height * 0f to shade, Ambience.Height to shade))
    }
}

// How many steps the glow's shade is worked out at down its height.
private const val GlowSteps = 8

// The shade for the glow: at each depth, what the glow's brightest colour
// (`peak`, from the cover as it is) comes to there, faded as the glow
// fades into the page (`glow` at the top, none at its foot).
fun glowShade(peak: Int, glow: Float): PageShade {
    val base = OctoColors.Background.toArgb()
    val stops = (0..GlowSteps).map { i ->
        val t = i / GlowSteps.toFloat()
        Ambience.Height * t to shadeFor(overlay(peak, glow * (1f - t), base))
    }
    return if (stops.all { it.second <= 0f }) PageShade.None else PageShade(stops)
}

// The shade for a wash lying evenly behind the page at `opacity`.
fun evenShade(peak: Int, opacity: Float): PageShade =
    PageShade.even(shadeFor(overlay(peak, opacity, OctoColors.Background.toArgb())))

// The shade for the window's colours as they are now: the ambience's
// style and strength and the cover it shows (`cover`, or Octo's own when
// nothing plays).
fun shadeFor(look: Appearance, cover: WashCover?, playing: Boolean): PageShade {
    if (!look.ambientGlow || cover == null) return PageShade.None
    return when {
        look.ambience == AmbienceStyle.Immersive -> {
            val most = cover.pageOpacity(look.glowStrength)
            evenShade(cover.peak, if (playing) most else minOf(most, quietOpacity(look.glowStrength)))
        }
        playing -> glowShade(cover.glowPeak, glowOpacity(look.glowStrength))
        else -> evenShade(cover.peak, quietOpacity(look.glowStrength))
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
    // The same prepared copy the ambience draws from (kept, so no extra work).
    val cover by produceState<WashCover?>(null, coverId, playing, connection, tuning) {
        if (!playing || connection == null) {
            value = withContext(Dispatchers.Default) { OctoArt.cover(tuning) }
        } else {
            app.washCovers.follow(connection.client, coverId, tuning).collect { value = it }
        }
    }
    val target = shadeFor(look, cover, playing)
    // The shape follows at once; how dark it is eases like the colours do.
    val most by animateFloatAsState(target.most, tween(motionScale().ms(CoverFadeMs.toInt())), label = "page shade")
    val top = target.most
    if (top <= 0f) return if (most <= 0f) PageShade.None else PageShade.even(most)
    return PageShade(target.stops.map { (at, shade) -> at to shade * (most / top) })
}

// Draws the shade behind what follows, for a page whose top is `top` below
// the window's.
fun Modifier.pageShade(shade: PageShade, top: Dp): Modifier = drawBehind {
    if (shade.most <= 0f) return@drawBehind
    val start = -top.toPx()
    val end = start + (shade.stops.last().first.toPx().coerceAtLeast(1f))
    val span = end - start
    val stops = shade.stops.map { (at, s) -> ((at.toPx() - 0f) / span).coerceIn(0f, 1f) to Color.Black.copy(alpha = s) }.toTypedArray()
    drawRect(Brush.verticalGradient(colorStops = stops, startY = start, endY = end))
}
